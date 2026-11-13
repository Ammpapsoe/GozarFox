#!/bin/sh
set -eu
binary=$1
resolver=$(mktemp)
printf 'nameserver 192.0.2.53\noptions timeout:1\n' > "$resolver"
trap 'rm -f "$resolver"' EXIT HUP INT TERM
run_case() {
 sudo unshare --mount --net --fork -- env GOZAR_TEST_BINARY="$binary" GOZAR_TEST_RESOLV="$resolver" GOZAR_LIVE_TUN_TEST=1 GOZAR_DEFAULT_MODE="$1" GOZAR_PROTECT_TEST_IP="$2" GOZAR_EXPECT_PROTECT_IF="$3" GOZAR_SPECIAL_CASE="${4:-normal}" sh -eu -c '
  mount --make-rprivate /
  mount --bind "$GOZAR_TEST_RESOLV" /etc/resolv.conf
  ip link set lo up
  ip link add gozartest0 type dummy
  ip addr add 192.0.2.2/24 dev gozartest0
  ip link set gozartest0 up
  ip link add gozaralt0 type dummy
  ip addr add 203.0.113.2/24 dev gozaralt0
  ip link set gozaralt0 up
  if [ "$GOZAR_DEFAULT_MODE" = direct ]; then
    ip route add default dev gozartest0
  else
    ip route add default via 192.0.2.1 dev gozartest0
  fi
  if [ "$GOZAR_SPECIAL_CASE" = existing ]; then
    ip route add "$GOZAR_PROTECT_TEST_IP"/32 dev "$GOZAR_EXPECT_PROTECT_IF"
  fi
  if [ "$GOZAR_SPECIAL_CASE" = dnschange ]; then export GOZAR_LIVE_DNS_CHANGE_TEST=1; fi
  "$GOZAR_TEST_BINARY" -test.v -test.run "^TestLiveKernelTunnel$" -test.timeout 75s
  if [ "$GOZAR_SPECIAL_CASE" = dnschange ]; then
    test "$(cat /etc/resolv.conf)" = "nameserver 192.0.2.99"
    printf "nameserver 192.0.2.53\noptions timeout:1\n" > /etc/resolv.conf
  else
    test "$(cat /etc/resolv.conf)" = "$(printf "nameserver 192.0.2.53\noptions timeout:1")"
  fi
  if ip route show | grep -Eq "^(0\.0\.0\.0/1|128\.0\.0\.0/1)"; then exit 1; fi
  if [ "$GOZAR_SPECIAL_CASE" = existing ]; then
    ip route show exact "$GOZAR_PROTECT_TEST_IP"/32 | grep -q "dev $GOZAR_EXPECT_PROTECT_IF"
  elif ip route show exact "$GOZAR_PROTECT_TEST_IP"/32 | grep -q .; then exit 1; fi
  echo "Resolver and routes verified for $GOZAR_DEFAULT_MODE / $GOZAR_SPECIAL_CASE via $GOZAR_EXPECT_PROTECT_IF"
 '
}
run_case gateway 192.0.2.42 gozartest0
run_case direct 192.0.2.42 gozartest0
run_case gateway 203.0.113.42 gozaralt0
run_case gateway 203.0.113.42 gozaralt0 existing
run_case gateway 192.0.2.42 gozartest0 dnschange
