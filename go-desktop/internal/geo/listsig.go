package geo

import (
	"crypto/ed25519"
	"encoding/hex"
	"errors"
	"strings"
)

// ListKeyHex pins the Ed25519 key that signs packaging/ir-domains.txt. It is
// deliberately separate from the application update key, so compromise of
// one signing domain does not extend to the other.
const ListKeyHex = "bd4c9be650ce400976e334c9d37a50fc03d9eddd72c2369e3ae0ca8669c97e88"

// The prefix binds a signature to this list format and prevents reuse of any
// other Ed25519 signature (for example an update manifest) as a list signature.
const listSignaturePrefix = "GOZAR-IR-DOMAINS-V1\n"

// ErrListSignature is returned for every failure: missing, malformed, wrong-key
// or tampered signatures all fail closed.
var ErrListSignature = errors.New("split-tunnel list signature is invalid")

// maxSignatureText bounds the detached signature file (128 hex characters plus
// optional whitespace) before any decoding work is done.
const maxSignatureText = 256

// VerifyList checks a detached signature over the exact list bytes with the
// pinned list key.
func VerifyList(list, signature []byte) error {
	public, err := hex.DecodeString(ListKeyHex)
	if err != nil {
		return ErrListSignature
	}
	return VerifyListWith(ed25519.PublicKey(public), list, signature)
}

// VerifyListWith is the verification core; production callers use VerifyList.
func VerifyListWith(public ed25519.PublicKey, list, signature []byte) error {
	if len(public) != ed25519.PublicKeySize || len(signature) > maxSignatureText {
		return ErrListSignature
	}
	text := strings.TrimSpace(string(signature))
	if len(text) != 2*ed25519.SignatureSize {
		return ErrListSignature
	}
	sig, err := hex.DecodeString(text)
	if err != nil || len(sig) != ed25519.SignatureSize {
		return ErrListSignature
	}
	message := make([]byte, 0, len(listSignaturePrefix)+len(list))
	message = append(message, listSignaturePrefix...)
	message = append(message, list...)
	if !ed25519.Verify(public, message, sig) {
		return ErrListSignature
	}
	return nil
}
