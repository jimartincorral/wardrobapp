/**
 * Deciding whether a URL is safe for the app to fetch.
 *
 * URL import fetches whatever it is given, and what it is given does not
 * necessarily come from the user: a deep link carries an `importUrl`, and any web
 * page, message or QR code can open one. The page it fetches then supplies image
 * URLs which get fetched in turn.
 *
 * A phone sits *inside* a home network, so a URL naming a private address turns
 * this app into a way to reach things the page could never reach itself -- a
 * router's admin endpoint, a printer, a NAS, anything on the LAN. That is the
 * hole these checks close: only publicly routable hosts, and the check is
 * re-applied after redirects, since a permitted URL can redirect anywhere.
 *
 * Pure, so every range below is actually exercised in a test rather than
 * reasoned about.
 */

/** Why a URL was refused, in words worth showing someone. */
export class UnsafeUrlError extends Error {}

/** Schemes the importer will fetch. Anything else is not a web page. */
const ALLOWED_PROTOCOLS = ['http:', 'https:'];

/**
 * Host suffixes that name something on the local network by convention.
 *
 * These resolve differently on every network, which is the point of them -- so a
 * URL using one is asking for whatever the *phone's* network calls that name.
 */
const PRIVATE_SUFFIXES = ['.local', '.localhost', '.internal', '.home.arpa', '.lan'];

/** Decimal octets of an IPv4 address, or null if it is not one. */
function ipv4Octets(hostname: string): number[] | null {
  const parts = hostname.split('.');
  if (parts.length !== 4) return null;

  const octets = parts.map(part => {
    // Rejecting anything but plain decimal on purpose: 0x7f.0.0.1 and 017700000001
    // are both 127.0.0.1 to a resolver, and both would sail past a check that
    // only understood decimal.
    if (!/^\d{1,3}$/.test(part)) return NaN;
    return Number(part);
  });

  if (octets.some(octet => Number.isNaN(octet) || octet > 255)) return null;
  return octets;
}

/**
 * True for an IPv4 address that is not routable on the public internet.
 *
 * The ranges are named rather than collapsed into arithmetic so each one can be
 * recognised: every one of them is somewhere a phone can reach and a web page
 * cannot.
 */
function isPrivateIpv4([a, b]: number[]): boolean {
  if (a === 0) return true;                          // "this network"
  if (a === 10) return true;                         // private
  if (a === 127) return true;                        // loopback
  if (a === 169 && b === 254) return true;           // link-local, incl. cloud metadata
  if (a === 172 && b >= 16 && b <= 31) return true;  // private
  if (a === 192 && b === 168) return true;           // private
  if (a === 100 && b >= 64 && b <= 127) return true; // carrier NAT
  if (a === 192 && b === 0) return true;             // protocol assignments
  if (a === 198 && (b === 18 || b === 19)) return true; // benchmarking
  if (a >= 224) return true;                         // multicast and reserved
  return false;
}

/** An IPv6 literal as URL.hostname gives it: bracketed, lowercase. */
function isPrivateIpv6(hostname: string): boolean {
  if (!hostname.startsWith('[') || !hostname.endsWith(']')) return false;
  const address = hostname.slice(1, -1).toLowerCase();

  if (address === '::1' || address === '::') return true;
  // Unique local (fc00::/7) and link-local (fe80::/10).
  if (/^f[cd][0-9a-f]{2}:/.test(address)) return true;
  if (/^fe[89ab][0-9a-f]:/.test(address)) return true;
  // An IPv4 address wearing an IPv6 coat: ::ffff:127.0.0.1.
  const mapped = address.match(/^::ffff:(\d{1,3}(?:\.\d{1,3}){3})$/);
  if (mapped) {
    const octets = ipv4Octets(mapped[1]);
    return octets === null || isPrivateIpv4(octets);
  }

  return false;
}

/**
 * True when a hostname is somewhere on the public internet.
 *
 * Errs towards refusing: a hostname this cannot categorise is refused rather
 * than fetched, because the cost of a false refusal is one import that does not
 * work, and the cost of a false pass is the app acting on the user's network.
 */
export function isPubliclyRoutableHost(hostname: string): boolean {
  const host = hostname.trim().toLowerCase().replace(/\.$/, '');
  if (!host) return false;

  if (PRIVATE_SUFFIXES.some(suffix => host.endsWith(suffix))) return false;

  if (isPrivateIpv6(host)) return false;
  // Any other IPv6 literal: allowed, having failed the private tests above.
  if (host.startsWith('[')) return true;

  const octets = ipv4Octets(host);
  if (octets) return !isPrivateIpv4(octets);

  // A bare name with no dot is an intranet name -- "router", "nas", "localhost"
  // -- resolved by whatever the phone's network says it is. A real product page
  // has a domain. (This is what refuses "localhost"; an explicit check for it
  // would be a branch no test could reach.)
  if (!host.includes('.')) return false;

  // An address written in a form a resolver accepts and the check above does
  // not: 0x7f.0.0.1, 0177.0.0.1, 127.1. Recognised by every label being numeric
  // or hex-prefixed rather than by the characters used, since a real domain can
  // be spelled entirely out of a-f -- face.be is a domain, not an address.
  if (host.split('.').every(label => /^\d+$/.test(label) || /^0x[0-9a-f]+$/.test(label))) {
    return false;
  }

  return true;
}

/**
 * Normalize a URL for import, refusing anything the app should not fetch.
 *
 * Throws [UnsafeUrlError] with something worth showing, since every rejection
 * here is a URL somebody typed, shared or linked.
 */
export function safeImportUrl(input: string): string {
  const trimmed = input.trim();
  if (!trimmed) throw new UnsafeUrlError('A URL is required.');

  const withProtocol = /^[a-z][a-z0-9+.-]*:\/\//i.test(trimmed) ? trimmed : `https://${trimmed}`;

  let url: URL;
  try {
    url = new URL(withProtocol);
  } catch {
    throw new UnsafeUrlError('That does not look like a web address.');
  }

  if (!ALLOWED_PROTOCOLS.includes(url.protocol)) {
    throw new UnsafeUrlError('Only http and https addresses can be imported.');
  }

  // Credentials in a URL are a phishing shape -- https://real.example@evil.test
  // reads as the first host and fetches the second -- and no product page needs
  // them.
  if (url.username || url.password) {
    throw new UnsafeUrlError('That address carries a username or password, so it was not opened.');
  }

  if (!isPubliclyRoutableHost(url.hostname)) {
    throw new UnsafeUrlError(
      `${url.hostname} is on this device or its local network, so it was not opened.`
    );
  }

  url.hash = '';
  return url.toString();
}

/**
 * Check where a request actually ended up.
 *
 * A permitted URL can redirect to a private one, and the fetch has already
 * followed it by the time anything here runs -- so this is what stops the
 * *response* being read and parsed, rather than what stops the request. The one
 * request that reaches a redirect target is the residual risk; refusing to read
 * it means nothing comes back out of it.
 */
export function checkFetchedUrl(finalUrl: string | null | undefined, requestedUrl: string): void {
  // Some platforms leave response.url empty; there is nothing to check then, and
  // the requested URL was already checked.
  if (!finalUrl || finalUrl === requestedUrl) return;

  let url: URL;
  try {
    url = new URL(finalUrl);
  } catch {
    throw new UnsafeUrlError('That address redirected somewhere unreadable.');
  }

  if (!ALLOWED_PROTOCOLS.includes(url.protocol) || !isPubliclyRoutableHost(url.hostname)) {
    throw new UnsafeUrlError(
      `That address redirected to ${url.hostname}, on this device or its local network.`
    );
  }
}
