type MapCoordinate = readonly [longitude: number, latitude: number]

type MapCenter = {
  longitude: number
  latitude: number
}

export function calculateCenter(
  points: readonly MapCoordinate[],
  fallback: MapCenter,
): MapCenter {
  if (points.length === 0) {
    return fallback
  }

  const sum = points.reduce(
    (current, [longitude, latitude]) => ({
      longitude: current.longitude + longitude,
      latitude: current.latitude + latitude,
    }),
    { longitude: 0, latitude: 0 },
  )

  return {
    longitude: sum.longitude / points.length,
    latitude: sum.latitude / points.length,
  }
}
