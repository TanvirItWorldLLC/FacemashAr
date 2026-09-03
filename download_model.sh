#!/bin/bash
# Download MediaPipe Face Landmarker model
# Run this script to get the required .task model file

MODEL_URL="https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task"
OUTPUT_DIR="app/src/main/assets"
MODEL_FILE="face_landmarker.task"

echo "Creating assets directory..."
mkdir -p "$OUTPUT_DIR"

echo "Downloading Face Landmarker model..."
if command -v curl &> /dev/null; then
    curl -L -o "$OUTPUT_DIR/$MODEL_FILE" "$MODEL_URL"
elif command -v wget &> /dev/null; then
    wget -O "$OUTPUT_DIR/$MODEL_FILE" "$MODEL_URL"
else
    echo "Error: Neither curl nor wget found. Please install one of them."
    exit 1
fi

if [ -f "$OUTPUT_DIR/$MODEL_FILE" ]; then
    SIZE=$(du -h "$OUTPUT_DIR/$MODEL_FILE" | cut -f1)
    echo "Model downloaded successfully: $OUTPUT_DIR/$MODEL_FILE ($SIZE)"
else
    echo "Error: Download failed"
    exit 1
fi