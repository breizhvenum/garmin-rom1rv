// Copyright (C) 2026 breizhvenum. Licensed under the GNU GPL v3 (see LICENSE).
import { mtpList, mtpCopy, mtpClose } from '../native/GarminMtp';
import { importFitFromPath } from './ImportFit';

// Reads the newest activities of the watch over the USB cable (MTP, read-only) and imports the ones not
// seen yet. The names already handled are remembered so a file is never downloaded twice.
const SEEN_KEY = 'garmin_mtp_seen_v1';
function store(): any { try { return require('@react-native-async-storage/async-storage').default; } catch (e) { return null; } }
async function loadSeen(): Promise<string[]> {
  try { const st = store(); const v = st ? await st.getItem(SEEN_KEY) : null; return v ? JSON.parse(v) : []; } catch (e) { return []; }
}
async function saveSeen(names: string[]): Promise<void> {
  try { const st = store(); if (st) await st.setItem(SEEN_KEY, JSON.stringify(names.slice(-500))); } catch (e) { /* not kept: files are re-checked next time */ }
}

export interface WatchImportResult { onWatch: number; imported: number; exists: number; deleted: number; failed: number; skipped: number }

export async function importFromWatch(limit = 10): Promise<WatchImportResult> {
  const res: WatchImportResult = { onWatch: 0, imported: 0, exists: 0, deleted: 0, failed: 0, skipped: 0 };
  try {
    const files = await mtpList(limit);
    res.onWatch = files.length;
    const seen = await loadSeen();
    for (const f of files) {
      if (seen.includes(f.name)) { res.skipped++; continue; }
      try {
        const path = await mtpCopy(f.handle, f.name);
        const r = await importFitFromPath(path);
        if (r.status === 'imported') res.imported++; else if (r.status === 'exists') res.exists++; else res.deleted++;
        seen.push(f.name);   // imported, already there or deleted by the user: no need to download it again
      } catch (e) { res.failed++; }   // e.g. a non-GPS activity (no track): shown as failed, retried next time
    }
    await saveSeen(seen);
  } finally {
    await mtpClose().catch(() => {});
  }
  return res;
}
