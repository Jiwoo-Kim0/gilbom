import os
import cv2
import json
import argparse
from pathlib import Path
from tqdm import tqdm

def rotate_yolo_dataset(input_dir: str, output_dir: str = None, add_to_existing: bool = True):
    """
    Rotates YOLO format dataset (images & txt labels) 90 degrees counter-clockwise.
    
    YOLO Normalized Coordinate Transformation for 90 deg CCW:
        new_cx = cy
        new_cy = 1.0 - cx
        new_w  = h
        new_h  = w
        
    Args:
        input_dir: Path to YOLO dataset directory (containing images/ and labels/)
        output_dir: Path to output directory (if None, uses input_dir or input_dir_augmented)
        add_to_existing: If True, adds rotated images/labels alongside original data (e.g. filename_rot90ccw.jpg)
    """
    input_path = Path(input_dir)
    if output_dir:
        output_path = Path(output_dir)
    else:
        output_path = input_path if add_to_existing else input_path.parent / f"{input_path.name}_rotated90ccw"
        
    splits = ['train', 'val']
    total_processed = 0

    for split in splits:
        img_dir = input_path / "images" / split
        lbl_dir = input_path / "labels" / split
        
        if not img_dir.exists():
            continue
            
        out_img_dir = output_path / "images" / split
        out_lbl_dir = output_path / "labels" / split
        out_img_dir.mkdir(parents=True, exist_ok=True)
        out_lbl_dir.mkdir(parents=True, exist_ok=True)
        
        img_files = [f for f in img_dir.glob("*.*") if f.suffix.lower() in ['.jpg', '.jpeg', '.png', '.bmp']]
        print(f"\n>>> Processing split [{split}] ({len(img_files)} images)...")
        
        for img_file in tqdm(img_files, desc=f"Rotating {split} set"):
            # Avoid re-processing already rotated files
            if "_rot90ccw" in img_file.stem:
                continue
                
            # 1. Load and rotate image 90 deg CCW
            img = cv2.imread(str(img_file))
            if img is None:
                print(f"Warning: Could not read image {img_file}")
                continue
                
            rotated_img = cv2.rotate(img, cv2.ROTATE_90_COUNTERCLOCKWISE)
            
            # Destination image name
            out_img_name = f"{img_file.stem}_rot90ccw{img_file.suffix}" if add_to_existing else img_file.name
            cv2.imwrite(str(out_img_dir / out_img_name), rotated_img)
            
            # 2. Read and rotate labels
            lbl_file = lbl_dir / f"{img_file.stem}.txt"
            rotated_labels = []
            
            if lbl_file.exists():
                with open(lbl_file, 'r', encoding='utf-8') as f:
                    lines = f.readlines()
                    
                for line in lines:
                    line = line.strip()
                    if not line:
                        continue
                    parts = line.split()
                    cid = parts[0]
                    cx, cy, w, h = map(float, parts[1:5])
                    
                    # 90 deg CCW YOLO coordinate transformation formula
                    rcx = cy
                    rcy = max(0.0, min(1.0, 1.0 - cx))
                    rw = h
                    rh = w
                    
                    rotated_labels.append(f"{cid} {rcx:.6f} {rcy:.6f} {rw:.6f} {rh:.6f}")
                    
            # Save rotated label file
            out_lbl_name = f"{img_file.stem}_rot90ccw.txt" if add_to_existing else f"{img_file.stem}.txt"
            with open(out_lbl_dir / out_lbl_name, 'w', encoding='utf-8') as f:
                f.write("\n".join(rotated_labels) + ("\n" if rotated_labels else ""))
                
            total_processed += 1
            
    print(f"\n✅ Completed 90° CCW rotation for {total_processed} images & labels!")
    print(f"📁 Output saved at: {output_path.resolve()}")


def rotate_raw_json_dataset(input_dir: str, output_dir: str):
    """
    Rotates raw dataset with JSON polygon annotations 90 degrees counter-clockwise.
    
    Polygon Point Transformation for 90 deg CCW:
        new_x = y
        new_y = orig_width - x
    """
    input_path = Path(input_dir)
    output_path = Path(output_dir)
    
    categories = ['good', 'bad']
    total_processed = 0
    
    for category in categories:
        img_dir = input_path / "images" / category
        lbl_dir = input_path / "labels" / category
        
        if not lbl_dir.exists():
            continue
            
        out_img_dir = output_path / "images" / category
        out_lbl_dir = output_path / "labels" / category
        out_img_dir.mkdir(parents=True, exist_ok=True)
        out_lbl_dir.mkdir(parents=True, exist_ok=True)
        
        json_files = list(lbl_dir.glob("*.json"))
        print(f"\n>>> Processing raw JSON category [{category}] ({len(json_files)} annotations)...")
        
        for json_file in tqdm(json_files, desc=f"Rotating {category} JSONs"):
            try:
                with open(json_file, 'r', encoding='utf-8') as f:
                    data = json.load(f)
                    
                info = data.get('info', {})
                img_filename = info.get('filename')
                orig_w = info.get('width')
                orig_h = info.get('height')
                
                if not img_filename or not orig_w or not orig_h:
                    continue
                    
                img_path = img_dir / img_filename
                if not img_path.exists():
                    continue
                    
                # 1. Rotate image
                img = cv2.imread(str(img_path))
                if img is None:
                    continue
                rotated_img = cv2.rotate(img, cv2.ROTATE_90_COUNTERCLOCKWISE)
                
                out_img_name = f"{img_path.stem}_rot90ccw{img_path.suffix}"
                cv2.imwrite(str(out_img_dir / out_img_name), rotated_img)
                
                # 2. Update JSON info (swap width and height)
                info['width'] = orig_h
                info['height'] = orig_w
                info['filename'] = out_img_name
                
                # 3. Rotate polygon points
                for ann in data.get('annotations', []):
                    poly = ann.get('annotation_info', [])
                    rotated_poly = []
                    for pt in poly:
                        x, y = pt[0], pt[1]
                        nx = y
                        ny = orig_w - x
                        rotated_poly.append([nx, ny])
                    ann['annotation_info'] = rotated_poly
                    
                out_json_name = f"{json_file.stem}_rot90ccw.json"
                with open(out_lbl_dir / out_json_name, 'w', encoding='utf-8') as f:
                    json.dump(data, f, ensure_ascii=False, indent=2)
                    
                total_processed += 1
            except Exception as e:
                print(f"Error processing {json_file}: {e}")

    print(f"\n✅ Completed 90° CCW rotation for {total_processed} raw JSON files!")
    print(f"📁 Output saved at: {output_path.resolve()}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Rotate Tactile Block Dataset 90 Degrees Counter-Clockwise (CCW)")
    parser.add_argument("--format", type=str, choices=['yolo', 'json'], default='yolo', help="Dataset format: 'yolo' or 'json'")
    parser.add_argument("--input-dir", type=str, default=r"C:\Users\SSAFY\Desktop\yolo\dataset_yolo", help="Input dataset path")
    parser.add_argument("--output-dir", type=str, default=None, help="Output dataset path (optional)")
    parser.add_argument("--mode", type=str, choices=['add', 'new'], default='add', 
                        help="'add': Add rotated samples to existing dataset, 'new': Save only rotated dataset to new output-dir")
    
    args = parser.parse_args()
    
    if args.format == 'yolo':
        add_to_existing = (args.mode == 'add')
        rotate_yolo_dataset(args.input_dir, args.output_dir, add_to_existing=add_to_existing)
    else:
        out_dir = args.output_dir if args.output_dir else str(Path(args.input_dir).parent / "dataset_rotated90ccw")
        rotate_raw_json_dataset(args.input_dir, out_dir)
