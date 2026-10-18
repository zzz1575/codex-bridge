import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import vm from "node:vm";
import { toolOutputImageUrls } from "../src/tool-image.js";
const source = fs.readFileSync(new URL("../src/main.js", import.meta.url), "utf8");

test("generated image paths use the authenticated preview channel and share its cache", async () => {
  const requests = [];
  const context = vm.createContext({
    requestFilePreview: async (...args) => {
      requests.push(args);
      return {
        kind: "image",
        preview_url: "data:image/png;base64,c3ludGhldGlj",
        name: "generated.png",
      };
    },
    command: async () => {
      throw new Error("A file image must not require tool-content hydration");
    },
    toolOutputImageUrls,
  });
  vm.runInContext(
    source.slice(
      source.indexOf("const toolImagePreviewCache ="),
      source.indexOf("function toolCallImagesNode("),
    ),
    context,
  );
  const tool = {
    name: "image_generation",
    image_path: "/workspace/generated.png",
    status: "completed",
  };
  context.tool = tool;
  const [first, second] = await Promise.all([
    vm.runInContext('toolImagePreviews(tool, "fixture-thread")', context),
    vm.runInContext('toolImagePreviews(tool, "fixture-thread")', context),
  ]);
  assert.equal(first[0].url, "data:image/png;base64,c3ludGhldGlj");
  assert.equal(second[0].url, first[0].url);
  assert.equal(requests.length, 1);
  assert.equal(requests[0][1], "/workspace/generated.png");
  context.tool = { name: "exec_command", image_path: "/workspace/generated.png" };
  assert.equal(
    (await vm.runInContext('toolImagePreviews(tool, "fixture-thread")', context)).length,
    0,
  );
  assert.equal(requests.length, 1, "Unrelated tool paths must not become file previews");
});

test("typed sleep and dynamic sleep have the same tool identity for reconciliation", () => {
  const context = vm.createContext({});
  vm.runInContext(
    source.slice(
      source.indexOf("function appServerItemTool("),
      source.indexOf("function pendingNode("),
    ),
    context,
  );
  const typed = vm.runInContext('appServerItemTool({type:"sleep",id:"sleep-fixture"})', context);
  const dynamic = vm.runInContext(
    'appServerItemTool({type:"dynamicToolCall",id:"sleep-fixture",namespace:"clock",tool:"sleep"})',
    context,
  );
  assert.equal(typed.name, "clock.sleep");
  assert.equal(typed.id, dynamic.id);
  assert.equal(typed.name, dynamic.name);
  assert.equal(
    vm.runInContext('appServerItemTool({type:"imageGeneration",id:"image-fixture"}).name', context),
    "image_generation",
  );
});
