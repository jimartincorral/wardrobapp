import { describe, expect, it } from 'vitest';
import {
  UnsafeUrlError,
  checkFetchedUrl,
  isPubliclyRoutableHost,
  safeImportUrl,
} from './url-safety';

describe('what counts as a public host', () => {
  it('allows an ordinary domain', () => {
    for (const host of ['example.com', 'www.zara.com', 'shop.example.co.uk', 'xn--80ak6aa92e.com']) {
      expect(isPubliclyRoutableHost(host)).toBe(true);
    }
  });

  it('allows a public IP address', () => {
    for (const host of ['8.8.8.8', '1.1.1.1', '93.184.216.34']) {
      expect(isPubliclyRoutableHost(host)).toBe(true);
    }
  });

  it('refuses this device', () => {
    for (const host of ['localhost', 'LOCALHOST', '127.0.0.1', '127.1.2.3', '[::1]', '[::]']) {
      expect(isPubliclyRoutableHost(host)).toBe(false);
    }
  });

  it('refuses every private IPv4 range', () => {
    // A phone is inside the network these name, so a page that could not reach
    // them itself would be using the app to do it.
    for (const host of [
      '10.0.0.1',        // private
      '10.255.255.255',
      '172.16.0.1',      // private
      '172.31.255.254',
      '192.168.0.1',     // the router, most likely
      '192.168.1.254',
      '169.254.169.254', // link-local, and the cloud metadata address
      '100.64.0.1',      // carrier NAT
      '0.0.0.0',         // "this network"
      '224.0.0.1',       // multicast
      '255.255.255.255',
      '198.18.0.1',      // benchmarking
      '192.0.0.1',       // protocol assignments
    ]) {
      expect(isPubliclyRoutableHost(host)).toBe(false);
    }
  });

  it('allows the public addresses that sit next to those ranges', () => {
    // The boundaries, so the ranges are not quietly wider than intended.
    for (const host of ['11.0.0.1', '172.15.255.255', '172.32.0.1', '192.167.255.255',
                        '192.169.0.1', '100.63.255.255', '100.128.0.1', '9.255.255.255']) {
      expect(isPubliclyRoutableHost(host)).toBe(true);
    }
  });

  it('refuses four numbers that are not an address', () => {
    // 8.8.8.999 is not an IPv4 address, so a resolver treats it as a hostname
    // and it means nothing. Passing it through as "public" would be reading it
    // as an address that happens not to be private.
    for (const host of ['8.8.8.999', '1.2.3.256', '999.999.999.999']) {
      expect(isPubliclyRoutableHost(host)).toBe(false);
    }
  });

  it('allows a domain spelled entirely out of hex digits', () => {
    // The catch-all for oddly written addresses has to key on the shape of the
    // labels, not the characters: these are real domains.
    for (const host of ['face.be', 'abc.de', 'cafe.fr', 'dad.ad', 'be.ee']) {
      expect(isPubliclyRoutableHost(host)).toBe(true);
    }
  });

  it('refuses an address written to look like something else', () => {
    // A resolver accepts all of these as 127.0.0.1; a check that only understood
    // dotted decimal would let them through.
    for (const host of ['0x7f.0.0.1', '2130706433', '017700000001', '127.1', '0177.0.0.1',
                        '1.2.3.4.5', '0x7f.0x0.0x0.0x1', '10.0177.0.1']) {
      expect(isPubliclyRoutableHost(host)).toBe(false);
    }
  });

  it('refuses an IPv4 address dressed as IPv6', () => {
    expect(isPubliclyRoutableHost('[::ffff:127.0.0.1]')).toBe(false);
    expect(isPubliclyRoutableHost('[::ffff:192.168.1.1]')).toBe(false);
  });

  it('allows a public IPv6 address', () => {
    expect(isPubliclyRoutableHost('[2606:2800:220:1:248:1893:25c8:1946]')).toBe(true);
  });

  it('refuses private and link-local IPv6', () => {
    for (const host of ['[fc00::1]', '[fd12:3456::1]', '[fe80::1]', '[fe80::abcd]', '[feb0::1]']) {
      expect(isPubliclyRoutableHost(host)).toBe(false);
    }
  });

  it('refuses the names a local network gives itself', () => {
    for (const host of ['printer.local', 'nas.lan', 'api.internal', 'router.home.arpa', 'app.localhost']) {
      expect(isPubliclyRoutableHost(host)).toBe(false);
    }
  });

  it('refuses a bare intranet name', () => {
    // "router" or "nas" resolves to whatever the phone's network says it is. A
    // real product page has a domain.
    for (const host of ['router', 'nas', 'intranet', 'wiki']) {
      expect(isPubliclyRoutableHost(host)).toBe(false);
    }
  });

  it('ignores a trailing dot and surrounding space', () => {
    expect(isPubliclyRoutableHost('example.com.')).toBe(true);
    expect(isPubliclyRoutableHost(' 127.0.0.1 ')).toBe(false);
    expect(isPubliclyRoutableHost('')).toBe(false);
  });
});

describe('normalizing a URL for import', () => {
  it('accepts a product page and fills in the scheme', () => {
    expect(safeImportUrl('zara.com/uk/shirt-p123.html'))
      .toBe('https://zara.com/uk/shirt-p123.html');
  });

  it('keeps the query, drops the fragment', () => {
    expect(safeImportUrl('https://example.com/p?id=7#reviews'))
      .toBe('https://example.com/p?id=7');
  });

  it('refuses a scheme that is not a web page', () => {
    for (const url of ['file:///etc/passwd', 'ftp://example.com/x', 'javascript:alert(1)',
                       'content://media/external/images/1', 'data:text/html,hi']) {
      expect(() => safeImportUrl(url)).toThrow(UnsafeUrlError);
    }
  });

  it('refuses a URL naming this device or the local network', () => {
    for (const url of ['http://localhost:8080/admin', 'http://192.168.1.1/',
                       'http://169.254.169.254/latest/meta-data/', 'http://[::1]:3000/']) {
      expect(() => safeImportUrl(url)).toThrow(UnsafeUrlError);
    }
  });

  it('names the host it refused, so the message is worth reading', () => {
    expect(() => safeImportUrl('http://192.168.1.1/')).toThrow(/192\.168\.1\.1/);
  });

  it('refuses a URL carrying credentials', () => {
    // https://real.example@evil.test reads as the first host and fetches the
    // second, which is the whole point of writing it that way.
    expect(() => safeImportUrl('https://zara.com@evil.test/p')).toThrow(UnsafeUrlError);
    expect(() => safeImportUrl('https://user:pass@example.com/p')).toThrow(UnsafeUrlError);
  });

  it('refuses nothing at all', () => {
    expect(() => safeImportUrl('')).toThrow(UnsafeUrlError);
    expect(() => safeImportUrl('   ')).toThrow(UnsafeUrlError);
    expect(() => safeImportUrl('not a url')).toThrow(UnsafeUrlError);
  });
});

describe('checking where a request ended up', () => {
  it('accepts a redirect to another public page', () => {
    expect(() =>
      checkFetchedUrl('https://www.example.com/p', 'https://example.com/p')
    ).not.toThrow();
  });

  it('refuses a redirect onto the local network', () => {
    // The reason this exists: the initial URL passing says nothing about where
    // it sends you. By the time this runs the request has been made, so what it
    // stops is the response being read -- nothing comes back out of it.
    expect(() => checkFetchedUrl('http://192.168.1.1/admin', 'https://example.com/p'))
      .toThrow(UnsafeUrlError);
    expect(() => checkFetchedUrl('http://localhost:9000/', 'https://example.com/p'))
      .toThrow(UnsafeUrlError);
  });

  it('refuses a redirect to another scheme', () => {
    expect(() => checkFetchedUrl('file:///etc/hosts', 'https://example.com/p'))
      .toThrow(UnsafeUrlError);
  });

  it('says nothing when the platform did not report a final URL', () => {
    // Some platforms leave response.url empty. The requested URL was already
    // checked, so there is nothing further to say.
    expect(() => checkFetchedUrl(null, 'https://example.com/p')).not.toThrow();
    expect(() => checkFetchedUrl('', 'https://example.com/p')).not.toThrow();
    expect(() => checkFetchedUrl(undefined, 'https://example.com/p')).not.toThrow();
  });
});
