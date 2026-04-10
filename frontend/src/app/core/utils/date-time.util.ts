function padDateTimePart(value: number): string {
  return String(value).padStart(2, '0');
}

function parseDateTime(value: string | null): Date | null {
  if (value === null || value.length === 0) {
    return null;
  }
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) {
    return null;
  }
  return parsed;
}

export function formatDateTimeWithSeconds(value: string | null): string {
  if (value === null || value.length === 0) {
    return '—';
  }
  const date = parseDateTime(value);
  if (date === null) {
    return value;
  }
  return [
    `${date.getFullYear()}-${padDateTimePart(date.getMonth() + 1)}-${padDateTimePart(date.getDate())}`,
    `${padDateTimePart(date.getHours())}:${padDateTimePart(date.getMinutes())}:${padDateTimePart(date.getSeconds())}`,
  ].join(' ');
}

export function formatCompactDateTimeWithSeconds(value: string | null): string {
  if (value === null || value.length === 0) {
    return '—';
  }
  const date = parseDateTime(value);
  if (date === null) {
    return value;
  }
  return [
    `${padDateTimePart(date.getMonth() + 1)}-${padDateTimePart(date.getDate())}`,
    `${padDateTimePart(date.getHours())}:${padDateTimePart(date.getMinutes())}:${padDateTimePart(date.getSeconds())}`,
  ].join(' ');
}
