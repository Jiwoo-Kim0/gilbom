import os
import argparse
from pathlib import Path
from dataset_preprocessor import TactileBlockDatasetPreprocessor
from train_yolo import train_model
from infer_demo import TactileBlockInference

def main():
    parser = argparse.ArgumentParser(description="End-to-End Tactile Block Detection & Classification Pipeline (YOLOv11)")
    
    # Dataset arguments
    parser.add_argument("--raw-dir", type=str, default=r"C:\Users\SSAFY\Desktop\yolo\dataset", help="Raw dataset path")
    parser.add_argument("--yolo-dir", type=str, default=r"C:\Users\SSAFY\Desktop\yolo\dataset_yolo", help="YOLO formatted dataset path")
    parser.add_argument("--val-ratio", type=float, default=0.2, help="Validation set ratio")
    parser.add_argument("--max-samples", type=int, default=None, help="Limit max samples per class (e.g. 500 for fast demo/test)")
    parser.add_argument("--skip-prep", action="store_true", help="Skip dataset preprocessing if already generated")
    
    # Model & Training arguments
    parser.add_argument("--model", type=str, default="yolo11n.pt", help="YOLOv11 model size: yolo11n.pt, yolo11s.pt, etc.")
    parser.add_argument("--epochs", type=int, default=15, help="Number of training epochs")
    parser.add_argument("--batch", type=int, default=16, help="Training batch size")
    parser.add_argument("--imgsz", type=int, default=640, help="Image resolution")
    parser.add_argument("--device", type=str, default="0", help="GPU device index or 'cpu'")
    parser.add_argument("--project", type=str, default="runs/detect", help="Runs directory")
    parser.add_argument("--name", type=str, default="tactile_block_yolo11", help="Experiment name")
    parser.add_argument("--export", type=str, default="onnx", help="Export format for Jetson Orin Nano: onnx, engine, or empty")
    parser.add_argument("--skip-train", action="store_true", help="Skip training if model is already trained")
    
    # Inference arguments
    parser.add_argument("--infer-sample-count", type=int, default=5, help="Number of test images to run inference demo on")
    parser.add_argument("--out-dir", type=str, default="results/predictions", help="Directory to save prediction images")

    args = parser.parse_args()
    
    # ---------------------------------------------------------
    # STEP 1: Preprocess Dataset (Raw JSON -> YOLO Format)
    # ---------------------------------------------------------
    yaml_path = Path(args.yolo_dir) / "data.yaml"
    if not args.skip_prep or not yaml_path.exists():
        print("\n" + "="*60)
        print(" [STEP 1/4] Preprocessing Dataset & Creating YOLO Annotations")
        print("="*60)
        preprocessor = TactileBlockDatasetPreprocessor(
            raw_dataset_dir=args.raw_dir,
            output_dataset_dir=args.yolo_dir,
            val_ratio=args.val_ratio
        )
        yaml_path = preprocessor.prepare(max_samples_per_class=args.max_samples)
    else:
        print(f"\n[STEP 1/4] Skipping dataset preprocessing. Using existing data.yaml at: {yaml_path}")

    # ---------------------------------------------------------
    # STEP 2: Train YOLOv11 Model & Validate
    # ---------------------------------------------------------
    best_weights_path = Path(args.project) / args.name / "weights" / "best.pt"
    if not args.skip_train or not best_weights_path.exists():
        print("\n" + "="*60)
        print(" [STEP 2/4] Training YOLOv11 Model & Evaluating Performance")
        print("="*60)
        best_weights_path = train_model(
            data_yaml=str(yaml_path),
            model_name=args.model,
            epochs=args.epochs,
            batch_size=args.batch,
            img_size=args.imgsz,
            device=args.device,
            project=args.project,
            name=args.name,
            export_format=args.export
        )
    else:
        print(f"\n[STEP 2/4] Skipping training. Using existing trained model at: {best_weights_path}")

    # ---------------------------------------------------------
    # STEP 3: Jetson Orin Nano Export Summary
    # ---------------------------------------------------------
    print("\n" + "="*60)
    print(" [STEP 3/4] Edge Device (Jetson Orin Nano) Deployment Info")
    print("="*60)
    print(f"  PyTorch Model Weights: {best_weights_path}")
    onnx_file = best_weights_path.with_suffix(".onnx")
    if onnx_file.exists():
        print(f"  ONNX Exported Model:   {onnx_file}")
    print(f"  Jetson Orin Nano Export Command:")
    print(f"    yolo export model=\"{best_weights_path}\" format=engine half=True imgsz={args.imgsz}")

    # ---------------------------------------------------------
    # STEP 4: Run Inference Demo on Validation Images
    # ---------------------------------------------------------
    print("\n" + "="*60)
    print(" [STEP 4/4] Running Inference & Visualization Demo")
    print("="*60)
    
    val_images_dir = Path(args.yolo_dir) / "images" / "val"
    val_img_paths = list(val_images_dir.glob("*.*"))[:args.infer_sample_count]
    
    if val_img_paths and best_weights_path.exists():
        inferencer = TactileBlockInference(str(best_weights_path), conf_threshold=0.25)
        for img_p in val_img_paths:
            out_p = os.path.join(args.out_dir, f"demo_{img_p.name}")
            _, dets = inferencer.predict_image(str(img_p), save_path=out_p)
            print(f"  Processed {img_p.name} -> Detections: {dets}")
    else:
        print("  No validation images found or model weights missing for inference demo.")
        
    print("\n" + "="*60)
    print(" Pipeline execution successfully completed!")
    print("="*60)

if __name__ == "__main__":
    main()
