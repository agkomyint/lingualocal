"use client";
import * as ort from "onnxruntime-web/wasm";

let configured = false;
export function onnx() {
  if (!configured) {
    configured = true;
    ort.env.wasm.wasmPaths = "/ort/";
    ort.env.wasm.numThreads = 1;
    ort.env.logLevel = "error";
  }
  return ort;
}
export type { InferenceSession, Tensor } from "onnxruntime-web/wasm";
