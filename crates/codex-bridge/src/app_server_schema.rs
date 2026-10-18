//! App-server protocol baseline compiled into codex-bridge.
//!
//! Audited against `codex-cli 0.163.0` using:
//! `codex app-server generate-json-schema --experimental --out ./schema`.
//! The unchanged generated `v2/ThreadItemsListResponse.json` fixture has SHA-256
//! `0ecf37ff62571c8411aab271ea65d22be4da1108cc2fd2dffb15a7034bfedb28`.
//! Update this value together with the generated-schema audit and compatibility tests.

pub const APP_SERVER_SCHEMA_VERSION: &str = "0.163.0";

#[cfg(test)]
mod tests {
    use serde_json::Value;
    use std::collections::BTreeSet;

    #[test]
    fn generated_0163_thread_item_variants_and_media_fields_are_audited() {
        let schema: Value = serde_json::from_str(include_str!(
            "../tests/fixtures/app-server-0.163.0-thread-items.json"
        ))
        .unwrap();
        let variants = schema
            .pointer("/definitions/ThreadItem/oneOf")
            .unwrap()
            .as_array()
            .unwrap();
        let names = variants
            .iter()
            .map(|variant| {
                variant
                    .pointer("/properties/type/enum/0")
                    .unwrap()
                    .as_str()
                    .unwrap()
            })
            .collect::<BTreeSet<_>>();
        assert_eq!(
            names,
            BTreeSet::from([
                "userMessage",
                "hookPrompt",
                "agentMessage",
                "functionCallOutput",
                "plan",
                "reasoning",
                "commandExecution",
                "fileChange",
                "mcpToolCall",
                "dynamicToolCall",
                "collabAgentToolCall",
                "subAgentActivity",
                "webSearch",
                "imageView",
                "sleep",
                "imageGeneration",
                "enteredReviewMode",
                "exitedReviewMode",
                "contextCompaction",
            ])
        );
        for (name, fields) in [
            ("sleep", vec!["id", "durationMs"]),
            (
                "imageGeneration",
                vec![
                    "id",
                    "status",
                    "result",
                    "revisedPrompt",
                    "failure",
                    "savedPath",
                    "transparentBackground",
                ],
            ),
            (
                "functionCallOutput",
                vec!["id", "name", "namespace", "output"],
            ),
            (
                "commandExecution",
                vec![
                    "id",
                    "command",
                    "commandActions",
                    "status",
                    "aggregatedOutput",
                    "exitCode",
                ],
            ),
        ] {
            let variant = variants
                .iter()
                .find(|variant| {
                    variant
                        .pointer("/properties/type/enum/0")
                        .and_then(Value::as_str)
                        == Some(name)
                })
                .unwrap();
            for field in fields {
                assert!(variant["properties"].get(field).is_some(), "{name}.{field}");
            }
        }
    }
}
