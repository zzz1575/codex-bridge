export function pastedFiles(clipboard) {
  const items = [...(clipboard?.items || [])]
    .filter((item) => item.kind === "file")
    .map((item) => item.getAsFile())
    .filter(Boolean);
  return items.length ? items : [...(clipboard?.files || [])];
}

export function richClipboardParts(
  html,
  plainText = "",
  parse = (value) => new DOMParser().parseFromString(value, "text/html"),
) {
  if (!/<img\b/i.test(html || "")) return { hasImages: false, imageSources: [], text: "" };
  const document = parse(html);
  const images = [...document.querySelectorAll("img")];
  const imageSources = images
    .map((image) => image.getAttribute("src")?.trim())
    .filter((source) => /^(?:data:image\/|blob:|https?:\/\/)/i.test(source || ""));
  let text = plainText || document.body?.textContent || "";
  for (const source of imageSources) text = text.replaceAll(source, "");
  return { hasImages: images.length > 0, imageSources, text: text.trim() };
}

export async function readClipboardImage(read = () => navigator.clipboard.read()) {
  if (typeof read !== "function") return null;
  const items = await read();
  for (const item of items) {
    const type = ["image/png", "image/jpeg", "image/webp", "image/gif"].find((mime) =>
      item.types.includes(mime),
    );
    if (type) {
      const blob = await item.getType(type);
      return new File([blob], `pasted-image.${type.split("/")[1]}`, { type });
    }
  }
  return null;
}

export async function imageFileFromSource(source, fetchImage = fetch) {
  if (source.startsWith("data:") && source.length > 32 * 1024 * 1024) {
    throw new Error("copied image is too large");
  }
  const response = await fetchImage(source, { credentials: "same-origin" });
  if (!response.ok) throw new Error(`image request failed (${response.status})`);
  if (Number(response.headers.get("content-length")) > 24 * 1024 * 1024) {
    throw new Error("copied image is too large");
  }
  const blob = await response.blob();
  if (!blob.type.startsWith("image/") || !blob.size || blob.size > 24 * 1024 * 1024) {
    throw new Error("copied image is unavailable");
  }
  return new File([blob], "pasted-image", { type: blob.type });
}
