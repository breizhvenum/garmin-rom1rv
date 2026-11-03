// Copyright (C) 2026 breizhvenum. Licensed under the GNU GPL v3 (see LICENSE).
// Part of rom1rv, derived from the Sommet project by skinnie (https://github.com/skinnie/sommet), also GPL v3.
import RNFS from 'react-native-fs';
import { pickGpxFile } from '../native/AmbitUsbModule';
import { base64ToBytes, fitToGpx, gpxToPoints, parseFit } from './FitParser';
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

/** Same import for a file already on the phone (the USB reader copies the watch's files to the cache).
 *  Accepts a Garmin .fit or a .gpx (recognised by its content, whatever the name). */
export async function importFitFromPath(picked: string): Promise<ImportFitResult> {
  const head = base64ToBytes(await RNFS.read(picked, 64, 0, 'base64'));
  const isFit = head.length >= 12 && String.fromCharCode(head[8], head[9], head[10], head[11]) === '.FIT';
  let gpx: string;
  let sport: string | null = null;
  let bytes: Uint8Array | null = null;
  if (isFit) {
    bytes = base64ToBytes(await RNFS.readFile(picked, 'base64'));
    try { sport = parseFit(bytes).sport; } catch { throw new Error("Ce fichier n'est pas un FIT exploitable (ou sans trace GPS)."); }
    gpx = fitToGpx(bytes, sport || 'Garmin rom1rv');
  } else {
    gpx = await RNFS.readFile(picked, 'utf8');
    if (!/<gpx[\s>]/i.test(gpx)) throw new Error("Ce fichier n'est ni un FIT ni un GPX.");
    try { gpxToPoints(gpx); } catch { throw new Error('Ce GPX ne contient pas de trace avec horodatage.'); }
  }
  const meta = extractGpxMetadata(gpx);
  if (!meta.date) throw new Error('Date de la session introuvable dans ce fichier.');
  const id = meta.date.replace(/[^0-9T]/g, '').substring(0, 15);   // same id rule as the SML import

  if (await isActivityDeleted(id)) return { status: 'deleted', id };
  if (await isActivitySynced(id)) return { status: 'exists', id };

  const gpxPath = await writeGpxFile(id, gpx, false);
  if (!gpxPath) return { status: 'exists', id };
  if (isFit) await adoptFitFile(id, picked, true);   // the original FIT is kept next to the GPX as <id>.fit
  else await RNFS.unlink(picked).catch(() => {});     // a GPX import: the stored GPX is the source file

  const type = sport || meta.activityType;
  await markActivitySynced({
    id, synced_at: Date.now(), gpx_path: gpxPath, date: meta.date,
    duration_s: meta.durationS, distance_m: meta.distanceM, d_plus: meta.dPlus,
    activity_type: type,
  });
  await attributeMoveToGear(id, type, meta.distanceM, meta.durationS, meta.date);
  return { status: 'imported', id, distanceM: meta.distanceM, durationS: meta.durationS };
}
