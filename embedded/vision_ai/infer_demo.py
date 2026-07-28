import os
import argparse
from pathlib import Path
import cv2
from ultralytics import YOLO

class TactileBlockInference:
    """
    Inference & Visualization class for Tactile Paving Block (점자블럭) detection & classification.
    Visualizes detected blocks as 'Good (정상)' [Green] or 'Bad (불량)' [Red].
    """
    
    CLASS_COLORS = {
        0: (0, 255, 0),   # Green for good (정상)
        1: (0, 0, 255)    # Red for bad (불량)
    }
    
    CLASS_LABELS = {
        0: "Good (정상)",
        1: "Bad (불량)"
    }
    
    def __init__(self, model_path: str, conf_threshold: float = 0.25):
        self.model = YOLO(model_path)
        self.conf_threshold = conf_threshold
        
    def predict_image(self, img_path: str, save_path: str = None):
        """Runs inference on a single image and optionally saves visual output."""
        img = cv2.imread(img_path)
        if img is None:
            print(f"Error: Unable to load image at {img_path}")
            return None, []
            
        results = self.model(img, conf=self.conf_threshold)[0]
        detections = []
        
        for box in results.boxes:
            cls_id = int(box.cls[0].item())
            conf = float(box.conf[0].item())
            x1, y1, x2, y2 = map(int, box.xyxy[0].tolist())
            
            color = self.CLASS_COLORS.get(cls_id, (255, 255, 255))
            label_text = f"{self.CLASS_LABELS.get(cls_id, f'Class {cls_id}')} {conf:.2f}"
            
            # Draw bounding box
            cv2.rectangle(img, (x1, y1), (x2, y2), color, 3)
            
            # Draw label background box
            (tw, th), _ = cv2.getTextSize(label_text, cv2.FONT_HERSHEY_SIMPLEX, 0.7, 2)
            cv2.rectangle(img, (x1, y1 - th - 10), (x1 + tw + 10, y1), color, -1)
            cv2.putText(img, label_text, (x1 + 5, y1 - 5), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (255, 255, 255), 2)
            
            detections.append({
                'class_id': cls_id,
                'class_name': self.CLASS_LABELS.get(cls_id),
                'confidence': conf,
                'bbox': [x1, y1, x2, y2]
            })
            
        if save_path:
            os.makedirs(os.path.dirname(save_path), exist_ok=True)
            cv2.imwrite(save_path, img)
            print(f"Saved prediction visual to: {save_path}")
            
        return img, detections

    def predict_batch(self, image_folder: str, output_folder: str):
        """Runs inference on all images in a directory."""
        img_paths = list(Path(image_folder).glob("*.*"))
        print(f">>> Running inference on {len(img_paths)} images from {image_folder}...")
        
        results_summary = []
        for p in img_paths:
            if p.suffix.lower() in ['.jpg', '.jpeg', '.png', '.bmp']:
                save_p = os.path.join(output_folder, f"pred_{p.name}")
                _, dets = self.predict_image(str(p), save_path=save_p)
                results_summary.append({'image': p.name, 'detections': dets})
                
        return results_summary

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Tactile Block Inference & Visualization")
    parser.add_argument("--model", type=str, required=True, help="Path to best.pt or onnx model")
    parser.add_argument("--input", type=str, required=True, help="Path to image file or directory")
    parser.add_argument("--out-dir", type=str, default="results/predictions")
    parser.add_argument("--conf", type=float, default=0.25)
    
    args = parser.parse_args()
    inferencer = TactileBlockInference(args.model, conf_threshold=args.conf)
    
    if os.path.isdir(args.input):
        inferencer.predict_batch(args.input, args.out_dir)
    else:
        out_path = os.path.join(args.out_dir, f"pred_{os.path.basename(args.input)}")
        img, dets = inferencer.predict_image(args.input, save_path=out_path)
        print(f"Detections for {args.input}: {dets}")
