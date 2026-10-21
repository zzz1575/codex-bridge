use std::fs::{self, OpenOptions};
use std::os::unix::fs::{OpenOptionsExt, PermissionsExt};
use std::path::PathBuf;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use anyhow::{Context, Result};
use rusqlite::{params, Connection};
use serde::Serialize;

const RETENTION: Duration = Duration::from_secs(7 * 24 * 60 * 60);

#[derive(Debug, Clone)]
pub struct WeeklyUsageHistory {
    path: PathBuf,
}

#[derive(Debug, Serialize)]
pub struct WeeklyUsageSample {
    pub observed_at: i64,
    pub remaining_percent: Option<i64>,
    pub resets_at: Option<i64>,
    pub error_code: Option<String>,
}

impl WeeklyUsageHistory {
    pub fn new(path: PathBuf) -> Result<Self> {
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent)?;
            fs::set_permissions(parent, fs::Permissions::from_mode(0o700))?;
        }
        OpenOptions::new()
            .write(true)
            .create(true)
            .mode(0o600)
            .open(&path)
            .with_context(|| {
                format!(
                    "failed to create weekly usage history at {}",
                    path.display()
                )
            })?;
        fs::set_permissions(&path, fs::Permissions::from_mode(0o600))?;
        let history = Self { path };
        history.connect()?.execute_batch(
            "CREATE TABLE IF NOT EXISTS weekly_usage_samples (
                id INTEGER PRIMARY KEY,
                observed_at INTEGER NOT NULL,
                remaining_percent INTEGER,
                resets_at INTEGER,
                error_code TEXT
            );
            CREATE INDEX IF NOT EXISTS weekly_usage_samples_time
                ON weekly_usage_samples(observed_at);",
        )?;
        Ok(history)
    }

    fn connect(&self) -> Result<Connection> {
        let connection = Connection::open(&self.path)?;
        connection.busy_timeout(Duration::from_secs(2))?;
        Ok(connection)
    }

    pub fn record(
        &self,
        remaining_percent: Option<i64>,
        resets_at: Option<i64>,
        error_code: Option<&str>,
    ) -> Result<()> {
        let now = now_seconds();
        let mut connection = self.connect()?;
        let transaction = connection.transaction()?;
        transaction.execute(
            "INSERT INTO weekly_usage_samples
                (observed_at, remaining_percent, resets_at, error_code)
                VALUES (?1, ?2, ?3, ?4)",
            params![
                now,
                remaining_percent,
                resets_at,
                error_code.map(|code| code.chars().take(80).collect::<String>())
            ],
        )?;
        transaction.execute(
            "DELETE FROM weekly_usage_samples WHERE observed_at < ?1",
            params![now - RETENTION.as_secs() as i64],
        )?;
        transaction.commit()?;
        Ok(())
    }

    pub fn samples(&self) -> Result<Vec<WeeklyUsageSample>> {
        let connection = self.connect()?;
        let mut statement = connection.prepare(
            "SELECT observed_at, remaining_percent, resets_at, error_code
             FROM weekly_usage_samples WHERE observed_at >= ?1 ORDER BY observed_at, id",
        )?;
        let samples =
            statement.query_map(params![now_seconds() - RETENTION.as_secs() as i64], |row| {
                Ok(WeeklyUsageSample {
                    observed_at: row.get(0)?,
                    remaining_percent: row.get(1)?,
                    resets_at: row.get(2)?,
                    error_code: row.get(3)?,
                })
            })?;
        samples
            .collect::<rusqlite::Result<Vec<_>>>()
            .map_err(Into::into)
    }
}

fn now_seconds() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs() as i64
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn records_each_probe_and_filters_out_samples_older_than_seven_days() {
        let directory = std::env::temp_dir().join(format!(
            "weekly-usage-{}-{}",
            std::process::id(),
            now_seconds()
        ));
        let path = directory.join("samples.sqlite");
        let history = WeeklyUsageHistory::new(path.clone()).unwrap();
        let old = now_seconds() - RETENTION.as_secs() as i64 - 1;
        history
            .connect()
            .unwrap()
            .execute(
                "INSERT INTO weekly_usage_samples (observed_at, remaining_percent) VALUES (?1, 90)",
                params![old],
            )
            .unwrap();
        history.record(Some(65), Some(123), None).unwrap();
        history.record(None, None, Some("unavailable")).unwrap();
        let samples = history.samples().unwrap();
        assert_eq!(samples.len(), 2);
        assert_eq!(samples[0].remaining_percent, Some(65));
        assert_eq!(samples[1].error_code.as_deref(), Some("unavailable"));
        assert_eq!(
            history
                .connect()
                .unwrap()
                .query_row("SELECT count(*) FROM weekly_usage_samples", [], |row| row
                    .get::<_, i64>(
                    0
                ))
                .unwrap(),
            2
        );
        fs::remove_dir_all(directory).unwrap();
    }
}
