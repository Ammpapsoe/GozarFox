package engine

// Storage names are deliberately shared by the platform-specific openers.
const (
	dnsLockName       = ".fakedns-owner-v1"
	dnsMarkerName     = ".fakedns-initialized-v1"
	dnsDirectoryName  = "fakedns-v1"
	dnsHistoryName    = "history"
	dnsCheckpointName = "checkpoint"
)

var dnsMarker = []byte("GozarFox FakeDNS initialized v1\n")
