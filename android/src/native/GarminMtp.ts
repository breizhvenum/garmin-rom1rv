// Copyright (C) 2026 breizhvenum. Licensed under the GNU GPL v3 (see LICENSE).
import { NativeModules } from 'react-native';

// Read-only USB (MTP) access to a Garmin watch: see GarminMtpModule.kt.
const M: any = NativeModules.GarminMtp;

export interface MtpActivity { handle: number; name: string; size: number; modified: number }

/** The `limit` most recent .fit files in the watch's GARMIN/Activity folder (0 = all). */
export function mtpList(limit: number): Promise<MtpActivity[]> { return M.list(limit); }
/** Copies one file of the watch to the app's cache and returns its path. Call mtpList first (it opens the link). */
export function mtpCopy(handle: number, name: string): Promise<string> { return M.copyToCache(handle, name); }
export function mtpClose(): Promise<boolean> { return M.close(); }
