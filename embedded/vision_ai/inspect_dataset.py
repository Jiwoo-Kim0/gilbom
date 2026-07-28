import os
import json
from glob import glob
import cv2
from collections import Counter

dataset_dir = r"C:\Users\SSAFY\Desktop\yolo\dataset"

good_imgs = glob(os.path.join(dataset_dir, "images", "good", "*.*"))
bad_imgs = glob(os.path.join(dataset_dir, "images", "bad", "*.*"))

good_jsons = glob(os.path.join(dataset_dir, "labels", "good", "*.json"))
bad_jsons = glob(os.path.join(dataset_dir, "labels", "bad", "*.json"))

print(f"Good images: {len(good_imgs)}, Good JSONs: {len(good_jsons)}")
print(f"Bad images: {len(bad_imgs)}, Bad JSONs: {len(bad_jsons)}")

def analyze_jsons(json_paths, category):
    label_names = Counter()
    is_defects = Counter()
    ann_types = Counter()
    total_anns = 0
    missing_imgs = 0
    img_sizes = set()

    for jp in json_paths:
        with open(jp, 'r', encoding='utf-8') as f:
            data = json.load(f)
        info = data.get('info', {})
        fname = info.get('filename')
        img_w = info.get('width')
        img_h = info.get('height')
        img_sizes.add((img_w, img_h))

        img_path = os.path.join(dataset_dir, "images", category, fname)
        if not os.path.exists(img_path):
            missing_imgs += 1

        anns = data.get('annotations', [])
        total_anns += len(anns)
        for ann in anns:
            label_names[ann.get('label_name')] += 1
            is_defects[ann.get('is_defect')] += 1
            ann_types[ann.get('annotation_type')] += 1

    print(f"\n--- Category: {category} ---")
    print(f"Total Annotations: {total_anns}")
    print(f"Missing images matching JSON filename: {missing_imgs}")
    print(f"Unique image sizes: {img_sizes}")
    print(f"label_name counts: {dict(label_names)}")
    print(f"is_defect counts: {dict(is_defects)}")
    print(f"annotation_type counts: {dict(ann_types)}")

analyze_jsons(good_jsons, "good")
analyze_jsons(bad_jsons, "bad")
