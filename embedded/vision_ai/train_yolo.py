import os
import argparse
from pathlib import Path
from ultralytics import YOLO

def train_model(
    data_yaml: str,
    model_name: str = "yolo11n.pt",
    epochs: int = 30,
    batch_size: int = 16,
    img_size: int = 640,
    device: str = "0",
    project: str = "runs/detect",
    name: str = "tactile_block_yolo11",
    export_format: str = "onnx"
):
    """
    Trains a YOLOv11 model for Tactile Paving Block (점자블럭) detection & classification.
    Optimized for Jetson Orin Nano edge deployment.
    """
    print(f"==================================================")
    print(f"  Training YOLOv11 Tactile Block Model")
    print(f"  Base Model:    {model_name}")
    print(f"  Data Config:   {data_yaml}")
    print(f"  Epochs:        {epochs}")
    print(f"  Batch Size:    {batch_size}")
    print(f"  Image Size:    {img_size}")
    print(f"  Target Device: {device}")
    print(f"==================================================")

    # Load YOLOv11 pretrained model
    model = YOLO(model_name)

    # Train model
    results = model.train(
        data=data_yaml,
        epochs=epochs,
        batch=batch_size,
        imgsz=img_size,
        device=device,
        project=project,
        name=name,
        exist_ok=True,
        pretrained=True,
        verbose=True,
        # Edge device augmentation optimizations
        hsv_h=0.015,
        hsv_s=0.7,
        hsv_v=0.4,
        degrees=10.0,
        translate=0.1,
        scale=0.5,
        fliplr=0.5
    )

    print("\n>>> Training complete! Validating best model...")
    # Validate best model
    val_results = model.val()
    
    # Save best model path
    best_weights_path = Path(project) / name / "weights" / "best.pt"
    print(f"\n>>> Best weights saved at: {best_weights_path}")

    # Export model for Jetson Orin Nano (ONNX / TensorRT Engine)
    if export_format:
        print(f"\n>>> Exporting model to {export_format.upper()} format for Jetson Orin Nano deployment...")
        try:
            exported_path = model.export(format=export_format, imgsz=img_size, half=True if export_format == 'engine' else False)
            print(f">>> Export successful: {exported_path}")
        except Exception as e:
            print(f"=== Warning: Export failed (e.g. CUDA TensorRT drivers missing for engine export): {e} ===")
            print("=== You can export directly on Jetson Orin Nano using: yolo export model=best.pt format=engine half=True ===")

    return best_weights_path

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Train YOLOv11 for Tactile Block Detection & Classification")
    parser.add_argument("--data", type=str, default=r"C:\Users\SSAFY\Desktop\yolo\dataset_yolo\data.yaml")
    parser.add_argument("--model", type=str, default="yolo11n.pt", help="yolo11n.pt or yolo11s.pt")
    parser.add_argument("--epochs", type=int, default=30)
    parser.add_argument("--batch", type=int, default=16)
    parser.add_argument("--imgsz", type=int, default=640)
    parser.add_argument("--device", type=str, default="0", help="0 or cpu")
    parser.add_argument("--project", type=str, default="runs/detect")
    parser.add_argument("--name", type=str, default="tactile_block_yolo11")
    parser.add_argument("--export", type=str, default="onnx", help="onnx or engine or leave empty")
    
    args = parser.parse_args()
    
    train_model(
        data_yaml=args.data,
        model_name=args.model,
        epochs=args.epochs,
        batch_size=args.batch,
        img_size=args.imgsz,
        device=args.device,
        project=args.project,
        name=args.name,
        export_format=args.export
    )
