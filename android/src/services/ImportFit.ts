import RNFS from 'react-native-fs';
import { pickGpxFile } from '../native/AmbitUsbModule';
import { base64ToBytes, fitToGpx, parseFit } from './FitParser';
import { extractGpxMetadata } from './GpxParser';
import { writeGpxFile, adoptFitFile } from './GpxService';
import { isActivitySynced, isActivityDeleted, markActivitySynced } from '../database/db';
import { attributeMoveToGear } from './GearAutoAssign';

export type ImportFitResult =
  | { status: 'imported'; id: string; distanceM: number; durationS: number }
  | { status: 'exists'; id: string }
  | { status: 'deleted'; id: string };

/** Adds a session from a .fit the user picks (copied from the watch, or exported from Garmin Connect). */
export async function importFitActivity(): Promise<ImportFitResult> {
  const picked = await pickGpxFile();   // rejects GPX_PICK_CANCELLED when the user backs out
  return importFitFromPath(picked);
}

/** Same import for a .fit already on the phone (the USB reader copies the watch's files to the cache). */
export async function importFitFromPath(picked: string): Promise<ImportFitResult> {
  const bytes = base64ToBytes(await RNFS.readFile(picked, 'base64'));
  let sport: string | null = null;
  try { sport = parseFit(bytes).sport; } catch { throw new Error("Ce fichier n'est pas un FIT exploitable (ou sans trace GPS)."); }
  const gpx = fitToGpx(bytes, sport || 'Garmin rom1rv');
  const meta = extractGpxMetadata(gpx);
  if (!meta.date) throw new Error('Date de la session introuvable dans ce FIT.');
  const id = meta.date.replace(/[^0-9T]/g, '').substring(0, 15);   // same id rule as the SML import

  if (await isActivityDeleted(id)) return { status: 'deleted', id };
  if (await isActivitySynced(id)) return { status: 'exists', id };

  const gpxPath = await writeGpxFile(id, gpx, false);
  if (!gpxPath) return { status: 'exists', id };
  await adoptFitFile(id, picked, true);   // the original FIT is kept next to the GPX as <id>.fit

  await markActivitySynced({
    id, synced_at: Date.now(), gpx_path: gpxPath, date: meta.date,
    duration_s: meta.durationS, distance_m: meta.distanceM, d_plus: meta.dPlus,
    activity_type: sport || meta.activityType,
  });
  await attributeMoveToGear(id, sport || meta.activityType, meta.distanceM, meta.durationS, meta.date);
  return { status: 'imported', id, distanceM: meta.distanceM, durationS: meta.durationS };
}
