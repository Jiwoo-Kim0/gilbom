import os
import json
import shutil
import random
from glob import glob
from pathlib import Path
from tqdm import tqdm

class TactileBlockDatasetPreprocessor:
    """
    Dataset Preprocessor for Tactile Paving Block (점자블럭) Classification & Detection.
    Converts raw JSON polygon annotations (good/bad) into YOLOv11 format.
    
    Classes:
        0: good (정상 점자블럭)
        1: bad  (불량 점자블럭)
    """
    
    def __init__(self, raw_dataset_dir: str, output_dataset_dir: str, val_ratio: float = 0.2, seed: int = 42):
        self.raw_dir = Path(raw_dataset_dir)
        self.output_dir = Path(output_dataset_dir)
        self.val_ratio = val_ratio
        self.seed = seed
        
        # Define class mappings
        self.class_names = {0: "good", 1: "bad"}
        
    @staticmethod
    def polygon_to_yolo_bbox(polygon, img_width, img_height):
        """Converts polygon points [[x1, y1], [x2, y2], ...] to YOLO normalized [cx, cy, w, h]."""
        xs = [p[0] for p in polygon]
        ys = [p[1] for p in polygon]
        
        min_x = max(0.0, min(xs))
        max_x = min(float(img_width), max(xs))
        min_y = max(0.0, min(ys))
        max_y = min(float(img_height), max(ys))
        
        box_w = max_x - min_x
        box_h = max_y - min_y
        
        # Avoid zero or negative dimensions
        if box_w <= 0 or box_h <= 0:
            return None
            
        cx = min_x + (box_w / 2.0)
        cy = min_y + (box_h / 2.0)
        
        # Normalize by image width & height
        norm_cx = cx / img_width
        norm_cy = cy / img_height
        norm_w = box_w / img_width
        norm_h = box_h / img_height
        
        # Clamp values to [0.0, 1.0]
        norm_cx = min(max(0.0, norm_cx), 1.0)
        norm_cy = min(max(0.0, norm_cy), 1.0)
        norm_w = min(max(0.0, norm_w), 1.0)
        norm_h = min(max(0.0, norm_h), 1.0)
        
        return norm_cx, norm_cy, norm_w, norm_h

    def _collect_samples(self, category: str, class_id: int, defect_target: str):
        """Reads JSON annotations and pairs them with image files for a given category."""
        labels_dir = self.raw_dir / "labels" / category
        images_dir = self.raw_dir / "images" / category
        
        json_files = list(labels_dir.glob("*.json"))
        samples = []
        
        for json_path in json_files:
            try:
                with open(json_path, 'r', encoding='utf-8') as f:
                    data = json.load(f)
                    
                info = data.get('info', {})
                img_filename = info.get('filename')
                img_w = info.get('width')
                img_h = info.get('height')
                
                if not img_filename or not img_w or not img_h:
                    continue
                    
                img_path = images_dir / img_filename
                if not img_path.exists():
                    continue
                    
                bboxes = []
                for ann in data.get('annotations', []):
                    # Filter annotations based on defect target: '정상' for good, '불량전체' for bad
                    if ann.get('is_defect') == defect_target:
                        poly = ann.get('annotation_info', [])
                        if poly:
                            bbox = self.polygon_to_yolo_bbox(poly, img_w, img_h)
                            if bbox:
                                bboxes.append((class_id, *bbox))
                                
                if bboxes:
                    samples.append({
                        'img_path': img_path,
                        'bboxes': bboxes,
                        'category': category
                    })
            except Exception as e:
                print(f"Error processing {json_path}: {e}")
                
        return samples

    def prepare(self, max_samples_per_class: int = None, copy_images: bool = True):
        """Preprocesses raw dataset and structures it into YOLO format."""
        print(">>> Collecting good (정상) samples...")
        good_samples = self._collect_samples("good", class_id=0, defect_target="정상")
        print(f"    Found {len(good_samples)} good samples.")
        
        print(">>> Collecting bad (불량) samples...")
        bad_samples = self._collect_samples("bad", class_id=1, defect_target="불량전체")
        print(f"    Found {len(bad_samples)} bad samples.")
        
        # Optional sample limit for fast debugging / prototyping
        if max_samples_per_class:
            random.seed(self.seed)
            good_samples = random.sample(good_samples, min(len(good_samples), max_samples_per_class))
            bad_samples = random.sample(bad_samples, min(len(bad_samples), max_samples_per_class))
            print(f"    Limited samples to max {max_samples_per_class} per class.")

        # Train / Val Split per class (Stratified)
        random.seed(self.seed)
        random.shuffle(good_samples)
        random.shuffle(bad_samples)
        
        good_val_cnt = int(len(good_samples) * self.val_ratio)
        bad_val_cnt = int(len(bad_samples) * self.val_ratio)
        
        train_samples = good_samples[good_val_cnt:] + bad_samples[bad_val_cnt:]
        val_samples = good_samples[:good_val_cnt] + bad_samples[:bad_val_cnt]
        
        print(f">>> Split Summary: Train={len(train_samples)}, Val={len(val_samples)}")

        # Create YOLO Directory Structure
        splits = ['train', 'val']
        for split in splits:
            (self.output_dir / "images" / split).mkdir(parents=True, exist_ok=True)
            (self.output_dir / "labels" / split).mkdir(parents=True, exist_ok=True)
            
        def _write_split(samples, split_name):
            img_out_dir = self.output_dir / "images" / split_name
            lbl_out_dir = self.output_dir / "labels" / split_name
            
            for item in tqdm(samples, desc=f"Writing {split_name} set"):
                src_img = item['img_path']
                dst_img = img_out_dir / src_img.name
                
                # Copy or symlink image file
                if copy_images:
                    if not dst_img.exists():
                        shutil.copy2(src_img, dst_img)
                        
                # Write YOLO format txt label file
                dst_lbl = lbl_out_dir / f"{src_img.stem}.txt"
                with open(dst_lbl, 'w', encoding='utf-8') as f:
                    for bbox in item['bboxes']:
                        cid, cx, cy, w, h = bbox
                        f.write(f"{cid} {cx:.6f} {cy:.6f} {w:.6f} {h:.6f}\n")

        _write_split(train_samples, 'train')
        _write_split(val_samples, 'val')

        # Create data.yaml
        yaml_path = self.output_dir / "data.yaml"
        # Convert path to posix standard
        abs_output_path = str(self.output_dir.resolve()).replace('\\', '/')
        
        yaml_content = f"""# Tactile Paving Block (점자블럭) Dataset
path: {abs_output_path}
train: images/train
val: images/val

names:
  0: good
  1: bad
"""
        with open(yaml_path, 'w', encoding='utf-8') as f:
            f.write(yaml_content)
            
        print(f">>> Dataset preparation complete! data.yaml saved at: {yaml_path}")
        return yaml_path

if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(description="Preprocess Tactile Block Dataset for YOLOv11")
    parser.add_argument("--raw-dir", type=str, default=r"C:\Users\SSAFY\Desktop\yolo\dataset")
    parser.add_argument("--out-dir", type=str, default=r"C:\Users\SSAFY\Desktop\yolo\dataset_yolo")
    parser.add_argument("--val-ratio", type=float, default=0.2)
    parser.add_argument("--max-samples", type=int, default=None, help="Limit samples per class for fast testing")
    args = parser.parse_args()
    
    preprocessor = TactileBlockDatasetPreprocessor(
        raw_dataset_dir=args.raw_dir,
        output_dataset_dir=args.out_dir,
        val_ratio=args.val_ratio
    )
    preprocessor.prepare(max_samples_per_class=args.max_samples)
