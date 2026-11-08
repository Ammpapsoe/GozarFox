package warp

import (
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"io"
	"math/big"
	"net/http"
	"net/netip"
	"strings"
	"time"
)

const (
	registrationURL = "https://api.cloudflareclient.com/v0a5641"
	clientVersion   = "a-6.38.9-5641"
	clientAgent     = "1.1.1.1/6.38.9-5641 (Android 16.0.0)"
	serverName      = "consumer-masque.cloudflareclient.com"
)

// Identity contains sensitive device credentials and must be encrypted at rest.
type Identity struct {
	ID         string `json:"id"`
	Token      string `json:"token"`
	PrivateKey string `json:"private_key"` // PKCS#8 DER, base64
	ServerKey  string `json:"server_key"`  // enrollment-supplied public SPKI
	IPv4       string `json:"ipv4"`
}

func validID(id string) bool {
	if len(id) == 0 || len(id) > 128 {
		return false
	}
	for _, ch := range id {
		if !((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') ||
			(ch >= '0' && ch <= '9') || ch == '.' || ch == '-' || ch == '_') {
			return false
		}
	}
	return true
}

func (i *Identity) privateKey() (*ecdsa.PrivateKey, error) {
	raw, err := base64.StdEncoding.DecodeString(i.PrivateKey)
	if err != nil {
		return nil, errors.New("invalid WARP device key")
	}
	parsed, err := x509.ParsePKCS8PrivateKey(raw)
	key, ok := parsed.(*ecdsa.PrivateKey)
	if err != nil || !ok || key.Curve != elliptic.P256() {
		return nil, errors.New("invalid WARP device key")
	}
	return key, nil
}

func (i *Identity) validBase() error {
	if i == nil || !validID(i.ID) || i.Token == "" || strings.ContainsAny(i.Token, "\r\n") {
		return errors.New("invalid WARP identity")
	}
	_, err := i.privateKey()
	return err
}

func (i *Identity) Ready() bool {
	if i.validBase() != nil {
		return false
	}
	address, err := netip.ParseAddr(i.IPv4)
	if err != nil || !address.Is4() || !address.IsPrivate() {
		return false
	}
	_, err = i.serverSPKI()
	return err == nil
}

func (i *Identity) serverSPKI() ([]byte, error) {
	var raw []byte
	if block, _ := pem.Decode([]byte(i.ServerKey)); block != nil {
		if block.Type != "PUBLIC KEY" {
			return nil, errors.New("invalid WARP server public key")
		}
		raw = block.Bytes
	} else {
		var err error
		raw, err = base64.StdEncoding.DecodeString(strings.Join(strings.Fields(i.ServerKey), ""))
		if err != nil {
			return nil, errors.New("invalid WARP server public key")
		}
	}
	parsed, err := x509.ParsePKIXPublicKey(raw)
	key, ok := parsed.(*ecdsa.PublicKey)
	if err != nil || !ok || key.Curve != elliptic.P256() {
		return nil, errors.New("invalid WARP server public key")
	}
	return x509.MarshalPKIXPublicKey(key)
}

func warpRequest(ctx context.Context, client *http.Client, method, path string, token string, body any) (map[string]any, error) {
	var input io.Reader
	if body != nil {
		raw, err := json.Marshal(body)
		if err != nil {
			return nil, err
		}
		input = bytes.NewReader(raw)
	}
	request, err := http.NewRequestWithContext(ctx, method, registrationURL+path, input)
	if err != nil {
		return nil, err
	}
	request.Header.Set("User-Agent", clientAgent)
	request.Header.Set("CF-Client-Version", clientVersion)
	request.Header.Set("Accept", "application/json")
	request.Header.Set("Content-Type", "application/json")
	if token != "" {
		request.Header.Set("Authorization", "Bearer "+token)
	}
	// Disable redirects even if the caller's client is permissive: the
	// registration bearer token must never reach a different origin.
	safeClient := *client
	safeClient.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	response, err := safeClient.Do(request)
	if err != nil {
		return nil, errors.New("WARP registration request failed")
	}
	defer response.Body.Close()
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		return nil, fmt.Errorf("WARP registration HTTP %d", response.StatusCode)
	}
	var result map[string]any
	if err := json.NewDecoder(io.LimitReader(response.Body, 1<<20)).Decode(&result); err != nil {
		return nil, errors.New("invalid WARP registration response")
	}
	if nested, ok := result["result"].(map[string]any); ok {
		result = nested
	}
	return result, nil
}

// Register must only be called after the end user has explicitly accepted the
// Cloudflare terms. persist is invoked immediately after initial registration so
// a failed enrollment can be resumed without silently creating more devices.
func Register(ctx context.Context, client *http.Client, consent bool, persist func(*Identity) error) (*Identity, error) {
	if !consent || client == nil || persist == nil {
		return nil, errors.New("WARP consent is required")
	}
	private, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return nil, err
	}
	der, err := x509.MarshalPKCS8PrivateKey(private)
	if err != nil {
		return nil, err
	}
	var placeholder [32]byte
	var serial [8]byte
	if _, err = rand.Read(placeholder[:]); err != nil {
		return nil, err
	}
	if _, err = rand.Read(serial[:]); err != nil {
		return nil, err
	}
	body := map[string]any{
		"key": base64.StdEncoding.EncodeToString(placeholder[:]), "install_id": "", "fcm_token": "",
		"tos": time.Now().UTC().Format("2006-01-02T15:04:05.000+00:00"), "model": "PC",
		"serial_number": hex.EncodeToString(serial[:]), "os_version": "", "key_type": "curve25519",
		"tunnel_type": "wireguard", "locale": "en_US",
	}
	response, err := warpRequest(ctx, client, "POST", "/reg", "", body)
	if err != nil {
		return nil, err
	}
	id, _ := response["id"].(string)
	token, _ := response["token"].(string)
	identity := &Identity{ID: id, Token: token, PrivateKey: base64.StdEncoding.EncodeToString(der)}
	if identity.validBase() != nil {
		return nil, errors.New("WARP returned an invalid identity")
	}
	if err := persist(identity); err != nil {
		return nil, errors.New("could not securely save WARP identity")
	}
	if err := identity.Enroll(ctx, client, persist); err != nil {
		return nil, err
	}
	return identity, nil
}

// Enroll resumes a partial registration or switches the same key to CONNECT-IP.
func (i *Identity) Enroll(ctx context.Context, client *http.Client, persist func(*Identity) error) error {
	if i.validBase() != nil || client == nil || persist == nil {
		return errors.New("invalid WARP identity")
	}
	key, _ := i.privateKey()
	pub, err := x509.MarshalPKIXPublicKey(&key.PublicKey)
	if err != nil {
		return err
	}
	body := map[string]string{"key": base64.StdEncoding.EncodeToString(pub), "key_type": "secp256r1", "tunnel_type": "masque"}
	response, err := warpRequest(ctx, client, "PATCH", "/reg/"+i.ID, i.Token, body)
	if err != nil {
		return err
	}
	config, ok := response["config"].(map[string]any)
	if !ok {
		return errors.New("missing WARP configuration")
	}
	peers, ok := config["peers"].([]any)
	if !ok || len(peers) == 0 {
		return errors.New("missing WARP server key")
	}
	peer, ok := peers[0].(map[string]any)
	if !ok {
		return errors.New("invalid WARP server key")
	}
	iface, ok := config["interface"].(map[string]any)
	if !ok {
		return errors.New("missing WARP interface")
	}
	addresses, ok := iface["addresses"].(map[string]any)
	if !ok {
		return errors.New("missing WARP address")
	}
	server, _ := peer["public_key"].(string)
	address, _ := addresses["v4"].(string)
	candidate := *i
	candidate.ServerKey = server
	candidate.IPv4 = strings.SplitN(address, "/", 2)[0]
	if !candidate.Ready() {
		return errors.New("invalid WARP enrollment")
	}
	if err := persist(&candidate); err != nil {
		return errors.New("could not securely save WARP enrollment")
	}
	*i = candidate
	return nil
}

func (i *Identity) tlsConfig() (*tls.Config, error) {
	if !i.Ready() {
		return nil, errors.New("WARP identity is incomplete")
	}
	key, _ := i.privateKey()
	pinned, _ := i.serverSPKI()
	serial, err := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 128))
	if err != nil {
		return nil, err
	}
	now := time.Now()
	template := &x509.Certificate{SerialNumber: serial, NotBefore: now.Add(-time.Hour), NotAfter: now.Add(24 * time.Hour),
		KeyUsage: x509.KeyUsageDigitalSignature, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageClientAuth}}
	cert, err := x509.CreateCertificate(rand.Reader, template, template, &key.PublicKey, key)
	if err != nil {
		return nil, err
	}
	cfg := &tls.Config{
		MinVersion: tls.VersionTLS12, ServerName: serverName, NextProtos: []string{"h2"},
		Certificates: []tls.Certificate{{Certificate: [][]byte{cert}, PrivateKey: key}},
		// WARP's server uses the device API's public-key trust anchor rather
		// than a public Web PKI hostname. Verification is mandatory below.
		InsecureSkipVerify: true, // verified against enrollment SPKI, not disabled
		VerifyPeerCertificate: func(rawCerts [][]byte, _ [][]*x509.Certificate) error {
			if len(rawCerts) == 0 {
				return errors.New("WARP server sent no certificate")
			}
			peer, err := x509.ParseCertificate(rawCerts[0])
			if err != nil {
				return err
			}
			actual, err := x509.MarshalPKIXPublicKey(peer.PublicKey)
			if err != nil || !bytes.Equal(actual, pinned) {
				return errors.New("WARP server key does not match enrollment")
			}
			return nil
		},
	}
	return cfg, nil
}
