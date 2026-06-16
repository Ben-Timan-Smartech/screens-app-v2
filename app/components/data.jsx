/* eslint-disable */
// Mock data used across all screens. Keeps artboards consistent.

const MOCK_BRANDS = [
  { id: 'dvx', name: 'DVX', videos: 42, products: ['Aurora', 'Core', 'Lumen'] },
  { id: 'sonos', name: 'SONOS', videos: 128, products: ['Arc', 'Era 300', 'Move', 'Ray', 'Sub Mini'] },
  { id: 'motorola', name: 'Motorola', videos: 67, products: ['Razr 50', 'Edge 50', 'G Stylus'] },
  { id: 'foreo', name: 'Foreo', videos: 54, products: ['Luna 4', 'Bear', 'UFO 3'] },
  { id: 'bose', name: 'Bose', videos: 183, products: ['QC Ultra', 'SoundLink Max', 'Smart Soundbar'] },
  { id: 'ember', name: 'Ember', videos: 21, products: ['Travel Mug', 'Cup², Tumbler'] },
];

// Demo fleet \u2014 IDs MUST match LOCATION_TAXONOMY.stores below, because the
// tablet's onboarding writes those IDs to its location and the CMS resolves
// registered tablets back to their store via this same id. Counts are
// derived dynamically from the live registry, so the static numbers here
// are placeholders.
const MOCK_STORES = [
  { id: 'tmrw-times-square',   name: 'tm:rw Times Square',         city: 'New York', country: 'US', region: 'NYC', total: 0, online: 0, warn: 0, offline: 0 },
  { id: 'smartech-selfridges', name: 'Smartech \u00b7 Selfridges LDN', city: 'London',   country: 'UK', region: 'UK/EU', total: 0, online: 0, warn: 0, offline: 0 },
  { id: 'smartech-kadewe',     name: 'Smartech \u00b7 KaDeWe',          city: 'Berlin',   country: 'DE', region: 'EU',  total: 0, online: 0, warn: 0, offline: 0 },
  { id: 'tmrw-rinascente',     name: 'tm:rw \u00b7 La Rinascente',      city: 'Rome',     country: 'IT', region: 'EU',  total: 0, online: 0, warn: 0, offline: 0 },
];

const MOCK_VIDEOS = [
  { id: 'v1', title: 'Arc Ultra — hero reveal', brand: 'SONOS', product: 'Arc', duration: '0:32', screens: 14 },
  { id: 'v2', title: 'Arc Ultra — room ambience', brand: 'SONOS', product: 'Arc', duration: '1:04', screens: 8 },
  { id: 'v3', title: 'Era 300 — spatial demo', brand: 'SONOS', product: 'Era 300', duration: '0:45', screens: 12 },
  { id: 'v4', title: 'Era 300 — Dolby Atmos explainer', brand: 'SONOS', product: 'Era 300', duration: '1:12', screens: 6 },
  { id: 'v5', title: 'Move 2 — outdoor lifestyle', brand: 'SONOS', product: 'Move', duration: '0:24', screens: 0 },
  { id: 'v6', title: 'Ray — dialogue demo', brand: 'SONOS', product: 'Ray', duration: '0:38', screens: 9 },
  { id: 'v7', title: 'Sub Mini — basslines', brand: 'SONOS', product: 'Sub Mini', duration: '0:28', screens: 11 },
  { id: 'v8', title: 'Arc — film suite', brand: 'SONOS', product: 'Arc', duration: '1:30', screens: 4 },
  { id: 'v9', title: 'Era 100 — small room', brand: 'SONOS', product: 'Era 300', duration: '0:22', screens: 7 },
  { id: 'v10', title: 'Sonos app — control center', brand: 'SONOS', duration: '0:52', screens: 18 },
  { id: 'v11', title: 'Beam Gen 2 — soundbar close-up', brand: 'SONOS', duration: '0:40', screens: 15 },
  { id: 'v12', title: 'Sonos Pro — installer loop', brand: 'SONOS', duration: '2:10', screens: 2 },
];

const MOCK_ACTIVITY = [
  { id: 'a1', kind: 'push', who: 'Alex Mendez', text: 'pushed Arc Ultra — hero reveal to 14 screens at Saks Fifth Avenue', time: '2 min ago', icon: 'upload' },
  { id: 'a2', kind: 'schedule', who: 'Jordan Park', text: 'activated schedule Holiday window, Selfridges London', time: '18 min ago', icon: 'schedule' },
  { id: 'a3', kind: 'offline', who: null, text: 'Screen Bose · Main floor went offline', time: '42 min ago', icon: 'offline', tone: 'err' },
  { id: 'a4', kind: 'upload', who: 'Mia Chen', text: 'uploaded 4 videos to Foreo / Luna 4', time: '1 hr ago', icon: 'upload' },
  { id: 'a5', kind: 'back', who: null, text: 'Screen Sonos · Era wall came back online', time: '1 hr ago', icon: 'check', tone: 'ok' },
  { id: 'a6', kind: 'sync', who: 'Alex Mendez', text: 'synced Motorola playlist to 6 screens across 3 stores', time: '3 hr ago', icon: 'sync' },
  { id: 'a7', kind: 'schedule', who: 'Jordan Park', text: 'created schedule Morning coffee window (9–12)', time: '5 hr ago', icon: 'schedule' },
  { id: 'a8', kind: 'upload', who: 'Mia Chen', text: 'uploaded 2 videos to Ember / Travel Mug', time: 'Yesterday', icon: 'upload' },
];

// Empty by design — every screen in the CMS now comes from live registered
// tablets via `/api/screens`. The list is built dynamically by `useFleet()`
// in ui.jsx. Leave this in place so any code still importing it doesn't
// blow up; it just means "no demo placeholders, only real tablets".
const MOCK_SCREENS_STORE = [];

/**
 * Convert a live screen record (from /api/screens) into the screen-row
 * shape the CMS views expect. Used everywhere a fleet view is rendered.
 *
 * `id` is the tablet's deviceId — that's also what the route at
 * `/screens/<storeId>/<id>` carries, so no separate mapping needed.
 */
const liveScreenToRow = (ls) => {
  const loc = ls.location || {};
  const items = ls.currentItems || [];
  const currentItem = items[0];
  return {
    id:         ls.deviceId,
    deviceId:   ls.deviceId,
    storeId:    loc.storeId || 'unassigned',
    screenCode: loc.screenCode || ls.deviceId.slice(0, 6),
    name:       [loc.screenCode, loc.concept].filter(Boolean).join(' · ') || (ls.name || 'Unnamed screen'),
    playing:    currentItem?.title || null,
    brand:      currentItem?.brand || null,
    status:     ls.online ? 'online' : 'offline',
    lastSeen:   ls.secondsSinceHeartbeat != null
                  ? (ls.secondsSinceHeartbeat < 60
                      ? `${Math.round(ls.secondsSinceHeartbeat)}s`
                      : `${Math.round(ls.secondsSinceHeartbeat / 60)}m`)
                  : 'never',
    orient:     (ls.orientation || 'LANDSCAPE').toLowerCase(),  // 'landscape' | 'portrait'
    tier:       ls.tier || '—',
    location:   loc,
    raw:        ls,
  };
};

// Schedules cleared until the user creates one. The Schedules screen still
// works — it just renders the empty state.
const MOCK_SCHEDULES = [];

// ─────────────────────────────────────────────────────────────
// Users — used by Settings → Users and by the on-tablet PIN check.
// PINs are 4-digit. Roles drive what each user can do on the tablet:
//   USER         — swap content only
//   ADMIN        — swap content + manage screens, schedules
//   SUPER_ADMIN  — full access incl. device logs and config
// ─────────────────────────────────────────────────────────────
const MOCK_USERS = [
  { id: 'u-owner',  name: 'Owner',                  initials: 'OW', email: 'owner@smartech.group',   role: 'SUPER_ADMIN',   pin: '9999', scope: 'All brands',           status: 'active'  },
  { id: 'u-staff',  name: 'Floor staff',            initials: 'FS', email: 'floor@smartech.group',   role: 'USER',          pin: '1111', scope: 'In-store swap only',   status: 'active'  },
  { id: 'u-alex',   name: 'Alex Mendez',            initials: 'AM', email: 'alex@smartech.group',    role: 'ADMIN',         pin: '4218', scope: 'All brands',           status: 'active'  },
  { id: 'u-jordan', name: 'Jordan Park',            initials: 'JP', email: 'jordan@smartech.group',  role: 'ADMIN',         pin: '7741', scope: 'All brands',           status: 'active'  },
  { id: 'u-mia',    name: 'Mia Chen',               initials: 'MC', email: 'mia@smartech.group',     role: 'BRAND_MANAGER', pin: '6302', scope: 'SONOS · Bose · DVX',   status: 'active'  },
  { id: 'u-theo',   name: 'Theo Reyes',             initials: 'TR', email: 'theo@smartech.group',    role: 'VIEWER',        pin: '3556', scope: 'Read-only',            status: 'active'  },
  { id: 'u-inga',   name: 'inga.lopez@external.io', initials: 'IL', email: 'Pending invite',         role: 'BRAND_MANAGER', pin: null,   scope: 'SONOS',                status: 'pending' },
];

const ROLE_LABEL = {
  SUPER_ADMIN:   'Super admin',
  ADMIN:         'Admin',
  BRAND_MANAGER: 'Brand manager',
  USER:          'In-store user',
  VIEWER:        'Viewer',
};

// ─────────────────────────────────────────────────────────────
// Location taxonomy — drives the cascading dropdowns on the
// tablet's Device admin screen and on every CMS form that needs
// to pin a screen to a specific physical location.
//
// Levels (top → bottom): region → city → store → concept → floor → table
// Screen Code is free text (e.g. "GF.A.1", "A1"), the only non-dropdown.
//
// Cascade rules:
//   city.region    must match the selected region
//   store.city     must match the selected city
//   table.floor    must match the selected floor (table id is "<floor>.<letter>")
//
// Concepts and floors are per-store in real life; for now they're
// global lists. Constrain per-store in a follow-up if needed.
// ─────────────────────────────────────────────────────────────
// v0.1.37: GLOBAL region + GLB city carry stores that aren't tied to
// a retail location — pop-up events, dev fixtures, anything ad-hoc.
// Custom stores added via Settings → Locations also land here unless
// the operator picks a real city.
const LOCATION_TAXONOMY = {
  regions: ['USA', 'UK', 'EU', 'GLOBAL'],
  cities: [
    { code: 'NYC', region: 'USA' },
    { code: 'LDN', region: 'UK'  },
    { code: 'BER', region: 'EU'  },
    { code: 'ROM', region: 'EU'  },
    { code: 'GLB', region: 'GLOBAL' },
  ],
  stores: [
    { id: 'tmrw-times-square',   name: 'tm:rw Times Square',          address: '220W 42nd Street, 10036',                                  city: 'NYC' },
    { id: 'smartech-selfridges', name: 'Smartech · Selfridges LDN',   address: '400 Oxford St, Marylebone, Selfridges, London W1A 1AB',   city: 'LDN' },
    { id: 'smartech-kadewe',     name: 'Smartech · KaDeWe',           address: 'Tauentzienstraße 21–24, 10789 Berlin',                     city: 'BER' },
    { id: 'tmrw-rinascente',     name: 'tm:rw · La Rinascente',       address: 'Galleria Alberto Sordi, 00187 Roma',                       city: 'ROM' },
    { id: 'events',              name: 'Events',                      address: 'Pop-up + event installations',                              city: 'GLB' },
    { id: 'test',                name: 'Test',                        address: 'Development & QA fixtures',                                 city: 'GLB' },
  ],
  concepts: ['Smartech', 'Playhouse', 'Sanctuary', 'Bikeshop', 'The Track', '7EVN', 'Cornershop', 'tm:rw Cafe'],
  floors: ['GF', 'MEZ', 'TF'],
  tables: ['GF.A', 'MEZ.A', 'TF.A', 'GF.B'],  // table id encodes floor as prefix
};

Object.assign(window, { MOCK_BRANDS, MOCK_STORES, MOCK_VIDEOS, MOCK_ACTIVITY, MOCK_SCREENS_STORE, MOCK_SCHEDULES, MOCK_USERS, ROLE_LABEL, LOCATION_TAXONOMY, liveScreenToRow });
