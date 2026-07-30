import type {
  GeoJsonCoordinate,
  GeoJsonPolygon,
  PatrolAreaBoundary,
} from './missionTypes'

const METERS_PER_LATITUDE_DEGREE = 111_320
const GEOMETRY_EPSILON = 1e-12

function roundCoordinate(value: number): number {
  return Number(value.toFixed(7))
}

export function createSquareBoundary(
  centerLatitude: number,
  centerLongitude: number,
  widthMeters: number,
): GeoJsonPolygon {
  const halfWidthMeters = widthMeters / 2
  const latitudeOffset = halfWidthMeters / METERS_PER_LATITUDE_DEGREE
  const longitudeScale = Math.cos(centerLatitude * Math.PI / 180)
  const longitudeOffset = halfWidthMeters
    / (METERS_PER_LATITUDE_DEGREE * longitudeScale)
  const south = roundCoordinate(centerLatitude - latitudeOffset)
  const north = roundCoordinate(centerLatitude + latitudeOffset)
  const west = roundCoordinate(centerLongitude - longitudeOffset)
  const east = roundCoordinate(centerLongitude + longitudeOffset)

  return {
    type: 'Polygon',
    coordinates: [[
      [west, south],
      [east, south],
      [east, north],
      [west, north],
      [west, south],
    ]],
  }
}

export function createPolygonBoundary(
  points: GeoJsonCoordinate[],
): GeoJsonPolygon {
  const ring = orderBoundaryPointsForPolygon(points).map(
    ([longitude, latitude]): GeoJsonCoordinate => [longitude, latitude],
  )
  ring.push([...ring[0]])
  return {
    type: 'Polygon',
    coordinates: [ring],
  }
}

export function polygonHasArea(points: GeoJsonCoordinate[]): boolean {
  return Math.abs(polygonSignedArea(points)) > GEOMETRY_EPSILON
}

export function polygonSignedArea(points: GeoJsonCoordinate[]): number {
  const twiceArea = points.reduce((area, point, index) => {
    const nextPoint = points[(index + 1) % points.length]
    return area
      + point[0] * nextPoint[1]
      - nextPoint[0] * point[1]
  }, 0)
  return twiceArea / 2
}

function compareCoordinates(
  left: GeoJsonCoordinate,
  right: GeoJsonCoordinate,
): number {
  return left[0] - right[0] || left[1] - right[1]
}

function distanceSquared(
  left: GeoJsonCoordinate,
  right: GeoJsonCoordinate,
): number {
  const averageLatitudeRadians = (
    (left[1] + right[1]) / 2 * Math.PI / 180
  )
  const longitudeDistance = (
    (left[0] - right[0]) * Math.cos(averageLatitudeRadians)
  )
  const latitudeDistance = left[1] - right[1]
  return longitudeDistance ** 2 + latitudeDistance ** 2
}

function coordinateDistance(
  left: GeoJsonCoordinate,
  right: GeoJsonCoordinate,
): number {
  return Math.sqrt(distanceSquared(left, right))
}

function crossProduct(
  start: GeoJsonCoordinate,
  end: GeoJsonCoordinate,
  point: GeoJsonCoordinate,
): number {
  return (
    (end[0] - start[0]) * (point[1] - start[1])
    - (end[1] - start[1]) * (point[0] - start[0])
  )
}

function isPointOnSegment(
  point: GeoJsonCoordinate,
  start: GeoJsonCoordinate,
  end: GeoJsonCoordinate,
): boolean {
  if (Math.abs(crossProduct(start, end, point)) > GEOMETRY_EPSILON) {
    return false
  }
  return (
    point[0] >= Math.min(start[0], end[0]) - GEOMETRY_EPSILON
    && point[0] <= Math.max(start[0], end[0]) + GEOMETRY_EPSILON
    && point[1] >= Math.min(start[1], end[1]) - GEOMETRY_EPSILON
    && point[1] <= Math.max(start[1], end[1]) + GEOMETRY_EPSILON
  )
}

function segmentsIntersect(
  firstStart: GeoJsonCoordinate,
  firstEnd: GeoJsonCoordinate,
  secondStart: GeoJsonCoordinate,
  secondEnd: GeoJsonCoordinate,
): boolean {
  const firstStartSide = crossProduct(firstStart, firstEnd, secondStart)
  const firstEndSide = crossProduct(firstStart, firstEnd, secondEnd)
  const secondStartSide = crossProduct(secondStart, secondEnd, firstStart)
  const secondEndSide = crossProduct(secondStart, secondEnd, firstEnd)
  if (
    firstStartSide * firstEndSide < -GEOMETRY_EPSILON
    && secondStartSide * secondEndSide < -GEOMETRY_EPSILON
  ) {
    return true
  }
  return (
    (
      Math.abs(firstStartSide) <= GEOMETRY_EPSILON
      && isPointOnSegment(secondStart, firstStart, firstEnd)
    )
    || (
      Math.abs(firstEndSide) <= GEOMETRY_EPSILON
      && isPointOnSegment(secondEnd, firstStart, firstEnd)
    )
    || (
      Math.abs(secondStartSide) <= GEOMETRY_EPSILON
      && isPointOnSegment(firstStart, secondStart, secondEnd)
    )
    || (
      Math.abs(secondEndSide) <= GEOMETRY_EPSILON
      && isPointOnSegment(firstEnd, secondStart, secondEnd)
    )
  )
}

function segmentsProperlyIntersect(
  firstStart: GeoJsonCoordinate,
  firstEnd: GeoJsonCoordinate,
  secondStart: GeoJsonCoordinate,
  secondEnd: GeoJsonCoordinate,
): boolean {
  const firstStartSide = crossProduct(firstStart, firstEnd, secondStart)
  const firstEndSide = crossProduct(firstStart, firstEnd, secondEnd)
  const secondStartSide = crossProduct(secondStart, secondEnd, firstStart)
  const secondEndSide = crossProduct(secondStart, secondEnd, firstEnd)
  return (
    firstStartSide * firstEndSide < -GEOMETRY_EPSILON
    && secondStartSide * secondEndSide < -GEOMETRY_EPSILON
  )
}

function removeDuplicatePoints(
  points: GeoJsonCoordinate[],
): GeoJsonCoordinate[] {
  const unique = new Map<string, GeoJsonCoordinate>()
  points.forEach(([longitude, latitude]) => {
    const key = `${longitude}:${latitude}`
    if (!unique.has(key)) {
      unique.set(key, [longitude, latitude])
    }
  })
  return [...unique.values()].sort(compareCoordinates)
}

function createNearestNeighborTour(
  points: GeoJsonCoordinate[],
): GeoJsonCoordinate[] {
  if (points.length <= 2) {
    return [...points]
  }
  const unvisited = [...points]
  const tour = [unvisited.shift() as GeoJsonCoordinate]
  while (unvisited.length > 0) {
    const current = tour.at(-1) as GeoJsonCoordinate
    let nearestIndex = 0
    let nearestDistance = distanceSquared(current, unvisited[0])
    for (let index = 1; index < unvisited.length; index += 1) {
      const candidateDistance = distanceSquared(current, unvisited[index])
      if (
        candidateDistance < nearestDistance - GEOMETRY_EPSILON
        || (
          Math.abs(candidateDistance - nearestDistance) <= GEOMETRY_EPSILON
          && compareCoordinates(unvisited[index], unvisited[nearestIndex]) < 0
        )
      ) {
        nearestIndex = index
        nearestDistance = candidateDistance
      }
    }
    tour.push(unvisited.splice(nearestIndex, 1)[0])
  }
  return tour
}

function improveTourWithTwoOpt(
  initialTour: GeoJsonCoordinate[],
): GeoJsonCoordinate[] {
  const tour = [...initialTour]
  if (tour.length < 4) {
    return tour
  }
  const maximumPasses = tour.length * tour.length
  for (let pass = 0; pass < maximumPasses; pass += 1) {
    let changed = false
    search:
    for (let first = 0; first < tour.length; first += 1) {
      const firstNext = (first + 1) % tour.length
      for (let second = first + 2; second < tour.length; second += 1) {
        const secondNext = (second + 1) % tour.length
        if (first === 0 && secondNext === 0) {
          continue
        }
        const currentDistance = (
          coordinateDistance(tour[first], tour[firstNext])
          + coordinateDistance(tour[second], tour[secondNext])
        )
        const swappedDistance = (
          coordinateDistance(tour[first], tour[second])
          + coordinateDistance(tour[firstNext], tour[secondNext])
        )
        if (
          !segmentsProperlyIntersect(
            tour[first],
            tour[firstNext],
            tour[second],
            tour[secondNext],
          )
          && swappedDistance >= currentDistance - GEOMETRY_EPSILON
        ) {
          continue
        }
        const reversed = tour.slice(firstNext, second + 1).reverse()
        tour.splice(firstNext, reversed.length, ...reversed)
        changed = true
        break search
      }
    }
    if (!changed) {
      break
    }
  }
  return tour
}

function rotateToSmallestCoordinate(
  points: GeoJsonCoordinate[],
): GeoJsonCoordinate[] {
  if (points.length === 0) {
    return []
  }
  let smallestIndex = 0
  for (let index = 1; index < points.length; index += 1) {
    if (compareCoordinates(points[index], points[smallestIndex]) < 0) {
      smallestIndex = index
    }
  }
  return [
    ...points.slice(smallestIndex),
    ...points.slice(0, smallestIndex),
  ]
}

export function orderBoundaryPointsForPolygon(
  points: GeoJsonCoordinate[],
): GeoJsonCoordinate[] {
  const uniquePoints = removeDuplicatePoints(points)
  const improvedTour = improveTourWithTwoOpt(
    createNearestNeighborTour(uniquePoints),
  )
  const counterClockwiseTour = polygonSignedArea(improvedTour) < 0
    ? [...improvedTour].reverse()
    : improvedTour
  return rotateToSmallestCoordinate(counterClockwiseTour)
}

export function hasBoundarySelfIntersections(
  points: GeoJsonCoordinate[],
): boolean {
  if (points.length < 4) {
    return false
  }
  for (let first = 0; first < points.length; first += 1) {
    const firstNext = (first + 1) % points.length
    for (let second = first + 1; second < points.length; second += 1) {
      const secondNext = (second + 1) % points.length
      if (
        first === second
        || firstNext === second
        || secondNext === first
      ) {
        continue
      }
      if (segmentsIntersect(
        points[first],
        points[firstNext],
        points[second],
        points[secondNext],
      )) {
        return true
      }
    }
  }
  return false
}

export function getBoundaryPolygons(
  boundary: PatrolAreaBoundary,
): GeoJsonCoordinate[][][] {
  return boundary.type === 'Polygon'
    ? [boundary.coordinates]
    : boundary.coordinates
}

function withoutClosingCoordinate(
  ring: GeoJsonCoordinate[],
): GeoJsonCoordinate[] {
  if (ring.length < 2) {
    return ring
  }
  const first = ring[0]
  const last = ring.at(-1)
  return last?.[0] === first[0] && last[1] === first[1]
    ? ring.slice(0, -1)
    : ring
}

export function getBoundaryOuterPoints(
  boundary: PatrolAreaBoundary,
): GeoJsonCoordinate[] {
  return getBoundaryPolygons(boundary).flatMap((polygon) => (
    withoutClosingCoordinate(polygon[0] ?? [])
  ))
}

export function calculateBoundaryAverageCenter(
  boundary: PatrolAreaBoundary,
): GeoJsonCoordinate | null {
  const points = getBoundaryOuterPoints(boundary)
  if (points.length === 0) {
    return null
  }
  const [longitudeTotal, latitudeTotal] = points.reduce(
    ([longitudeSum, latitudeSum], [longitude, latitude]) => [
      longitudeSum + longitude,
      latitudeSum + latitude,
    ],
    [0, 0],
  )
  return [
    longitudeTotal / points.length,
    latitudeTotal / points.length,
  ]
}
