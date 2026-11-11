import datetime
import json
import unittest

from tools.collect_bridges import MOAT, canonical_bridge, collect


FINGERPRINT = "A" * 40
CERTIFICATE = "A" * 86
OBFS4 = f"obfs4 8.8.8.8:443 {FINGERPRINT} cert={CERTIFICATE} iat-mode=0"
WEBTUNNEL = f"webtunnel 1.1.1.1:443 {'B' * 40} url=https://bridge.example.com/a1B2 ver=0.0.1"


class BridgeCollectorTest(unittest.TestCase):
    def test_obfs4_is_canonical_and_injection_is_rejected(self):
        self.assertEqual(OBFS4, canonical_bridge(OBFS4))
        self.assertIsNone(canonical_bridge(OBFS4 + "\nSocksPort 0.0.0.0:9050"))
        self.assertIsNone(canonical_bridge(OBFS4.replace("8.8.8.8", "127.0.0.1")))
        self.assertIsNone(canonical_bridge(OBFS4.replace("8.8.8.8", "192.168.1.1")))
        self.assertIsNone(canonical_bridge(OBFS4.replace("iat-mode=0", 'iat-mode=0"')))
        self.assertIsNone(canonical_bridge(OBFS4.replace("cert=", "certificate=")))

    def test_webtunnel_requires_public_endpoint_and_safe_https(self):
        self.assertEqual(WEBTUNNEL, canonical_bridge(WEBTUNNEL))
        self.assertIsNone(canonical_bridge(WEBTUNNEL.replace("1.1.1.1", "127.0.0.1")))
        self.assertIsNone(canonical_bridge(WEBTUNNEL.replace("https://", "http://")))
        self.assertIsNone(canonical_bridge(WEBTUNNEL.replace("bridge.example.com", "localhost")))
        self.assertIsNone(canonical_bridge(WEBTUNNEL + '"'))
        self.assertIsNone(canonical_bridge(WEBTUNNEL.replace("/a1B2", "/a%2Fb")))

    def test_sources_are_merged_deduplicated_and_bounded(self):
        def fetch(url):
            if url == MOAT:
                return json.dumps({"obfs4": [OBFS4], "webtunnel": [WEBTUNNEL]}).encode()
            return (OBFS4 + "\n" + OBFS4.replace("8.8.8.8", "10.0.0.1") + "\n").encode()

        now = datetime.datetime(2026, 11, 11, tzinfo=datetime.timezone.utc)
        document, counts = collect(fetch=fetch, now=now)
        self.assertEqual([OBFS4, WEBTUNNEL], document["bridges"])
        self.assertEqual(36 * 3600, document["expires_at"] - document["issued_at"])
        self.assertIn("tor-moat-builtin", document["sources"])
        self.assertEqual(2, counts["community-delta-kronecker"])
        self.assertEqual(2, counts["community-onionhop"])

    def test_official_outage_does_not_invent_community_trust(self):
        def fetch(url):
            if url == MOAT:
                raise OSError("offline")
            return (OBFS4 + "\n").encode()

        document, _ = collect(fetch=fetch, now=datetime.datetime.now(datetime.timezone.utc))
        self.assertNotIn("tor-moat-builtin", document["sources"])
        self.assertEqual([OBFS4], document["bridges"])

    def test_no_valid_public_bridge_fails_closed(self):
        self.assertRaises(ValueError, collect, fetch=lambda url: b"obfs4 127.0.0.1:443 invalid\n")


if __name__ == "__main__":
    unittest.main()
