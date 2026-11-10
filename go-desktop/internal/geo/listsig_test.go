package geo

import (
	"crypto/ed25519"
	"encoding/hex"
	"os"
	"strings"
	"testing"
)

func signList(priv ed25519.PrivateKey, list []byte) []byte {
	message := append([]byte(listSignaturePrefix), list...)
	return []byte(hex.EncodeToString(ed25519.Sign(priv, message)) + "\n")
}

func testKey(t *testing.T, fill byte) (ed25519.PublicKey, ed25519.PrivateKey) {
	t.Helper()
	seed := make([]byte, ed25519.SeedSize)
	for i := range seed {
		seed[i] = fill
	}
	priv := ed25519.NewKeyFromSeed(seed)
	return priv.Public().(ed25519.PublicKey), priv
}

func TestListKeyPinIsValidEd25519Key(t *testing.T) {
	key, err := hex.DecodeString(ListKeyHex)
	if err != nil || len(key) != ed25519.PublicKeySize {
		t.Fatalf("pinned list key is malformed: %v", err)
	}
}

func TestVerifyListAcceptsExactSignedBytes(t *testing.T) {
	pub, priv := testKey(t, 3)
	list := []byte("example.ir\nbank.example\n")
	if err := VerifyListWith(pub, list, signList(priv, list)); err != nil {
		t.Fatal(err)
	}
}

func TestVerifyListRejectsTamperedUnsignedWrongKeyAndUnprefixed(t *testing.T) {
	pub, priv := testKey(t, 3)
	otherPub, _ := testKey(t, 9)
	list := []byte("example.ir\nbank.example\n")
	sig := signList(priv, list)
	cases := map[string]struct {
		key  ed25519.PublicKey
		list []byte
		sig  []byte
	}{
		"tampered list":        {pub, []byte("example.ir\nevil.example\n"), sig},
		"unsigned (empty)":     {pub, list, nil},
		"malformed hex":        {pub, list, []byte("zz" + strings.Repeat("0", 126))},
		"truncated signature":  {pub, list, sig[:100]},
		"oversized signature":  {pub, list, []byte(strings.Repeat("a", maxSignatureText+1))},
		"wrong key":            {otherPub, list, sig},
		"unprefixed signature": {pub, list, []byte(hex.EncodeToString(ed25519.Sign(priv, list)))},
	}
	for name, c := range cases {
		if err := VerifyListWith(c.key, c.list, c.sig); err == nil {
			t.Errorf("%s: accepted", name)
		}
	}
}

// The committed packaging list must verify with the pinned production key.
// This test fails closed until CI has committed packaging/ir-domains.txt.sig.
func TestPackagedListVerifiesWithPinnedKey(t *testing.T) {
	list, err := os.ReadFile("../../../packaging/ir-domains.txt")
	if err != nil {
		t.Fatal(err)
	}
	sig, err := os.ReadFile("../../../packaging/ir-domains.txt.sig")
	if err != nil {
		t.Fatalf("missing detached list signature: %v", err)
	}
	if err := VerifyList(list, sig); err != nil {
		t.Fatal(err)
	}
}
