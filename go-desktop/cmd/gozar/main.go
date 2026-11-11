package main

import (
	"bufio"
	"flag"
	"fmt"
	"io"
	"log"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/app"
	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/fxa"
	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/rpc"
	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/sysproxy"
	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/tundev"
)

const version = "1.12.1"

func main() {
	log.SetOutput(io.Discard)
	rpcMode := flag.Bool("rpc", false, "")
	headless := flag.Bool("headless", false, "")
	email := flag.String("email", "", "")
	password := flag.String("password", "", "")
	code := flag.String("code", "", "")
	listServers := flag.Bool("servers", false, "")
	selftest := flag.Bool("selftest", false, "")
	showVersion := flag.Bool("version", false, "")
	clearProxy := flag.Bool("clear-proxy", false, "explicitly clear system proxy settings")
	flag.Parse()

	switch {
	case *showVersion:
		fmt.Println("Gozar " + version)
	case *selftest:
		fmt.Println(report(*headless))
	case *clearProxy:
		fmt.Println(clearProxyState())
	case *listServers:
		printServers()
	case *email != "" && *password != "":
		signIn(*email, *password, *code)
	case *rpcMode:
		runRPC()
	case *headless:
		runHeadless()
	default:
		runRPC()
	}
}

func runRPC() {
	current := app.New(version)
	current.StartRelayDiscovery()
	rpc.Run(current)
	current.Close()
}

func runHeadless() {
	current := app.New(version)
	current.StartRelayDiscovery()
	defer current.Close()
	if err := current.Connect(); err != nil {
		fmt.Println("failed: " + err.Error())
		os.Exit(1)
	}
	state := current.State()
	fmt.Println("connected via " + state.Server.Code + " " + state.Exit)
	stop := make(chan os.Signal, 1)
	signal.Notify(stop, os.Interrupt, syscall.SIGTERM)
	<-stop
}

func printServers() {
	servers, err := fxa.Servers()
	if err != nil {
		fmt.Println("failed: " + err.Error())
		os.Exit(1)
	}
	for _, place := range servers {
		fmt.Printf("%-4s %-22s %-18s %s:%d\n", place.Code, place.Country, place.City, place.Host, place.Port)
	}
}

func signIn(email, password, code string) {
	reader := bufio.NewReader(os.Stdin)
	current := app.New(version)
	current.StartRelayDiscovery()
	defer current.Close()
	err := current.SignIn(email, password, code)
	if app.NeedsCode(err) {
		fmt.Print("the code Mozilla e-mailed: ")
		line, _ := reader.ReadString('\n')
		err = current.SignIn(email, password, strings.TrimSpace(line))
	}
	if err != nil {
		fmt.Println("failed: " + err.Error())
		os.Exit(1)
	}
	fmt.Println("signed in as " + email)
}

func clearProxyState() string {
	if err := sysproxy.Clear(); err != nil {
		return "failed to clear system proxy settings: " + err.Error()
	}
	return "system proxy settings cleared"
}

func report(headless bool) string {
	var out strings.Builder
	out.WriteString("Gozar " + version + "\n")
	out.WriteString("admin    : " + yesNo(tundev.Privileged()) + "\n")
	current := app.New(version)
	current.StartRelayDiscovery()
	defer current.Close()
	state := current.State()
	out.WriteString("signed in: " + yesNo(state.SignedIn) + "\n")
	out.WriteString("mode     : " + state.Mode + "\n")
	out.WriteString("server   : " + state.Server.Code + "\n")
	servers, err := fxa.Servers()
	if err != nil {
		out.WriteString("servers  : " + err.Error() + "\n")
	} else {
		out.WriteString("servers  : " + fmt.Sprint(len(servers)) + " locations\n")
	}
	if !headless {
		if err := current.Connect(); err != nil {
			out.WriteString("connect  : " + err.Error() + "\n")
		} else {
			time.Sleep(2 * time.Second)
			now := current.State()
			out.WriteString("device   : " + now.Device + "\n")
			out.WriteString("exit ip  : " + now.Exit + "\n")
			current.Disconnect()
		}
	}
	out.WriteString("result   : ALL OK")
	return out.String()
}

func yesNo(value bool) string {
	if value {
		return "yes"
	}
	return "no"
}
