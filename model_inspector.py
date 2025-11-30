#!/usr/bin/env python3
"""
TFLite Model Inspector
Run this to check your model's expected input format
"""

import tensorflow as tf
import numpy as np

def inspect_tflite_model(model_path):
    """Inspect a TFLite model's input/output specifications"""
    
    # Load the model
    interpreter = tf.lite.Interpreter(model_path=model_path)
    interpreter.allocate_tensors()
    
    # Get input details
    input_details = interpreter.get_input_details()
    output_details = interpreter.get_output_details()
    
    print("=" * 60)
    print("MODEL INSPECTION REPORT")
    print("=" * 60)
    
    print("\n📥 INPUT TENSOR:")
    for i, inp in enumerate(input_details):
        print(f"  [{i}] Name: {inp['name']}")
        print(f"      Shape: {inp['shape']}")
        print(f"      Type: {inp['dtype']}")
        print(f"      Quantization: {inp.get('quantization', 'None')}")
        
        # Check if quantized
        if inp['dtype'] == np.uint8:
            print(f"      ⚠️  QUANTIZED MODEL - Use scale/zero_point")
            print(f"      Scale: {inp['quantization_parameters']['scales']}")
            print(f"      Zero Point: {inp['quantization_parameters']['zero_points']}")
    
    print("\n📤 OUTPUT TENSOR:")
    for i, out in enumerate(output_details):
        print(f"  [{i}] Name: {out['name']}")
        print(f"      Shape: {out['shape']}")
        print(f"      Type: {out['dtype']}")
    
    print("\n" + "=" * 60)
    print("EXPECTED INPUT FORMAT:")
    print("=" * 60)
    
    input_shape = input_details[0]['shape']
    input_dtype = input_details[0]['dtype']
    
    if input_dtype == np.float32:
        print("✅ Float32 model")
        print("   Normalize pixels to [0.0, 1.0]:")
        print("   normalized = pixel_value / 255.0")
        print("\n   OR if trained with ImageNet preprocessing:")
        print("   R = (pixel - 123.675) / 58.395")
        print("   G = (pixel - 116.28) / 57.12")
        print("   B = (pixel - 103.53) / 57.375")
    
    elif input_dtype == np.uint8:
        print("⚠️  Quantized Uint8 model")
        print("   Use pixels directly (0-255)")
        print("   No normalization needed!")
    
    print(f"\n   Expected input shape: {input_shape}")
    print(f"   Batch: {input_shape[0]}")
    print(f"   Height: {input_shape[1]}")
    print(f"   Width: {input_shape[2]}")
    print(f"   Channels: {input_shape[3]}")
    
    # Test inference
    print("\n" + "=" * 60)
    print("TESTING INFERENCE:")
    print("=" * 60)
    
    # Create dummy input
    test_input = np.random.rand(*input_shape).astype(np.float32)
    
    try:
        interpreter.set_tensor(input_details[0]['index'], test_input)
        interpreter.invoke()
        output = interpreter.get_tensor(output_details[0]['index'])
        
        print(f"✅ Inference successful!")
        print(f"   Output shape: {output.shape}")
        print(f"   Output range: [{output.min():.4f}, {output.max():.4f}]")
        
        # Analyze output format
        if len(output.shape) == 3 and output.shape[2] == 85:
            print("\n   Format: YOLOv5/v8 detection output")
            print(f"   Detections: {output.shape[1]}")
            print(f"   Values per detection: {output.shape[2]}")
            print("   Layout: [x, y, w, h, objectness, class0, class1, ..., class79]")
        
    except Exception as e:
        print(f"❌ Inference failed: {e}")
    
    print("\n" + "=" * 60)

if __name__ == "__main__":
    import sys
    
    if len(sys.argv) < 2:
        print("Usage: python model_inspector.py <path_to_model.tflite>")
        print("\nExample:")
        print("  python model_inspector.py app/src/main/assets/model.tflite")
        sys.exit(1)
    
    model_path = sys.argv[1]
    inspect_tflite_model(model_path)