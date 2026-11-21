/** Date and length labels, same as the Android screens. */
const pad = (n: number) => String(n).padStart(2, "0");
const MON = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
const DAY = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];

export const hm = (t: number) => { const d = new Date(t); return `${pad(d.getHours())}:${pad(d.getMinutes())}`; };
export const hms = (t: number) => { const d = new Date(t); return `${hm(t)}:${pad(d.getSeconds())}`; };
export const dMon = (t: number) => { const d = new Date(t); return `${d.getDate()} ${MON[d.getMonth()]}`; };
export const eeeDMon = (t: number) => `${DAY[new Date(t).getDay()]} ${dMon(t)}`;
export const dMonYear = (t: number) => `${dMon(t)} ${new Date(t).getFullYear()}`;

export const count = (n: number, word: string) => (n === 1 ? `1 ${word}` : `${n} ${word}s`);

export function duration(ms: number) {
  const s = Math.floor(ms / 1000);
  if (s < 60) return `${s} s`;
  if (s < 3600) return `${Math.floor(s / 60)} min`;
  return `${Math.floor(s / 3600)} h ${Math.floor((s % 3600) / 60)} min`;
}

/** Recorded length: 04:12, or 1:04:12 past an hour. */
export function length(ms: number) {
  const s = Math.floor(ms / 1000);
  return s >= 3600 ? `${Math.floor(s / 3600)}:${pad(Math.floor((s % 3600) / 60))}:${pad(s % 60)}` : `${pad(Math.floor(s / 60))}:${pad(s % 60)}`;
}

/** m:ss, or h:mm:ss past an hour. */
export function clockLength(s: number) {
  return s >= 3600 ? `${Math.floor(s / 3600)}:${pad(Math.floor((s % 3600) / 60))}:${pad(s % 60)}` : `${Math.floor(s / 60)}:${pad(s % 60)}`;
}

export function dayStart(offsetDays = 0) {
  const d = new Date();
  d.setHours(0, 0, 0, 0);
  d.setDate(d.getDate() + offsetDays);
  return d.getTime();
}

export const ageGroup = (t: number) => (t >= dayStart() ? "Today" : t >= dayStart(-6) ? "This week" : "Earlier");

/** "Today 14:05", "Yesterday 09:12", "Sat 15 Nov 18:30". */
export function dayLabel(t: number) {
  if (t >= dayStart()) return `Today ${hm(t)}`;
  if (t >= dayStart(-1)) return `Yesterday ${hm(t)}`;
  return `${eeeDMon(t)} ${hm(t)}`;
}
