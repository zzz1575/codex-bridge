export function sendFileUpload(
  file,
  encodedName,
  onProgress,
  createRequest = () => new XMLHttpRequest(),
  onResponse = () => {},
) {
  return new Promise((resolve, reject) => {
    const request = createRequest();
    request.open("POST", "/api/attachment-upload");
    request.withCredentials = true;
    request.timeout = 120_000;
    request.setRequestHeader("Content-Type", "application/octet-stream");
    request.setRequestHeader("X-Attachment-Name", encodedName);
    request.upload.onprogress = (event) => {
      if (event.lengthComputable && event.total) {
        onProgress(Math.min(99, Math.round((event.loaded / event.total) * 100)));
      }
    };
    request.onload = () => {
      onResponse(new TextEncoder().encode(request.responseText || "").byteLength, request.status);
      if (request.status < 200 || request.status >= 300) {
        const error = new Error(request.responseText || `upload failed (${request.status})`);
        error.status = request.status;
        reject(error);
      } else {
        try {
          resolve(JSON.parse(request.responseText));
        } catch {
          reject(new Error("upload returned invalid JSON"));
        }
      }
    };
    request.onerror = () => reject(new Error("upload failed: network error"));
    request.ontimeout = () => reject(new Error("upload timed out after 120000 ms"));
    request.send(file);
  });
}
