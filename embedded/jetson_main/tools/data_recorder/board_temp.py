"""Jetson board temperatures from sysfs thermal zones."""

import glob

# zone name -> temp file path, resolved once at import
_ZONES = {}
for _z in glob.glob("/sys/devices/virtual/thermal/thermal_zone*"):
    try:
        with open(_z + "/type") as f:
            _ZONES[f.read().strip()] = _z + "/temp"
    except OSError:
        pass

# tj = hottest junction on the SoC, the single number that matters for throttling
_WANTED = {"cpu": "cpu-thermal", "gpu": "gpu-thermal", "tj": "tj-thermal"}


def read_temps():
    """Return {'cpu': 43.4, 'gpu': 44.4, 'tj': 44.1} in Celsius; None if unreadable
    (cv* zones are power-gated and fail with EAGAIN - those are never requested)."""
    out = {}
    for key, zone in _WANTED.items():
        path = _ZONES.get(zone)
        temp = None
        if path:
            try:
                with open(path) as f:
                    temp = round(int(f.read()) / 1000.0, 1)
            except (OSError, ValueError):
                pass
        out[key] = temp
    return out
