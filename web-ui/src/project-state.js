export const EXPANDED_PROJECTS_STORAGE_KEY = "codex-bridge.expanded-projects.v1";

export function recentSidebarThreads(recentThreads, pinnedThreads, limit = 50) {
  const pinned = pinnedThreads.slice(0, limit);
  const pinnedIds = new Set(pinnedThreads.map((thread) => thread.id));
  const recent = recentThreads
    .filter((thread) => !pinnedIds.has(thread.id))
    .slice(0, Math.max(0, limit - pinned.length));
  return { pinned, recent };
}

export function sidebarProjectOrder(projects) {
  return [...projects].sort(
    (left, right) => Number(right.kind === "section") - Number(left.kind === "section"),
  );
}

export function sidebarProjectGroups(projects) {
  const sections = new Set(
    projects.filter((project) => project.kind === "section").map((p) => p.path),
  );
  return sidebarProjectOrder(projects)
    .filter((project) => !project.section_path || !sections.has(project.section_path))
    .map((project) => ({
      project,
      children:
        project.kind === "section"
          ? projects.filter((child) => child.section_path === project.path)
          : [],
    }));
}

export function storedExpandedProjects(storage) {
  try {
    const raw = storage.getItem(EXPANDED_PROJECTS_STORAGE_KEY);
    if (raw === null) return null;
    const paths = JSON.parse(raw);
    if (!Array.isArray(paths)) return null;
    return new Set(paths.filter((path) => typeof path === "string" && path.length > 0).slice(-100));
  } catch {
    return null;
  }
}

export function persistExpandedProjects(storage, expanded) {
  try {
    storage.setItem(
      EXPANDED_PROJECTS_STORAGE_KEY,
      JSON.stringify([...expanded].filter((path) => typeof path === "string").slice(-100)),
    );
  } catch {
    // Sidebar preferences are best effort when browser storage is unavailable.
  }
}
