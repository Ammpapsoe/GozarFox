include $(TOPDIR)/rules.mk

PKG_NAME:=gozar
PKG_VERSION:=1.10.4
PKG_RELEASE:=1
PKG_LICENSE:=AGPL-3.0-or-later
PKG_MAINTAINER:=Gozar contributors <noreply@github.com>

PKG_SOURCE:=$(PKG_NAME)-$(PKG_VERSION).tar.gz
PKG_SOURCE_URL:=https://github.com/bardia049b-stack/Gozar1/archive
PKG_SOURCE_PROTO:=git
PKG_SOURCE_VERSION:=v$(PKG_VERSION)-desktop
PKG_SOURCE_SUBDIR:=$(PKG_NAME)-$(PKG_VERSION)

PKG_BUILD_DEPENDS:=golang/host
PKG_BUILD_PARALLEL:=1
PKG_BUILD_FLAGS:=no-mips16

GO_PKG:=github.com/bardia049b-stack/Gozar1/go-desktop
GO_PKG_BUILD_PKG:=$$(GO_PKG)/cmd/gozar
GO_PKG_LDFLAGS_X:=$(GO_PKG)/cmd/gozar.version=$(PKG_VERSION)

include $(INCLUDE_DIR)/package.mk
include ../../lang/golang/golang-package.mk

define Package/gozar
  SECTION:=net
  CATEGORY:=Network
  SUBMENU:=Web Servers/Proxies
  TITLE:=Firefox Private Network client
  URL:=https://github.com/bardia049b-stack/Gozar1
  DEPENDS:=$(GO_ARCH_DEPENDS) +kmod-tun
endef

define Package/gozar/description
  Gozar routes the traffic of an OpenWrt device through a Firefox Private
  Network proxy. It ships the headless Go core, a procd service and its
  configuration in /etc/config/gozar.
endef

define Package/gozar/conffiles
/etc/config/gozar
endef

define Package/gozar/install
	$(INSTALL_DIR) $(1)/usr/bin
	$(INSTALL_BIN) $(GO_PKG_BUILD_BIN_DIR)/gozar $(1)/usr/bin/gozar-core
	$(INSTALL_DIR) $(1)/etc/init.d
	$(INSTALL_BIN) ./files/gozar.init $(1)/etc/init.d/gozar
	$(INSTALL_DIR) $(1)/etc/config
	$(INSTALL_CONF) ./files/gozar.config $(1)/etc/config/gozar
	$(INSTALL_DIR) $(1)/usr/share/gozar
	$(INSTALL_DATA) ./files/README $(1)/usr/share/gozar/README
endef

define Package/luci-app-gozar
  SECTION:=luci
  CATEGORY:=LuCI
  SUBMENU:=3. Applications
  TITLE:=LuCI support for Gozar
  DEPENDS:=+gozar +luci-base
  PKGARCH:=all
endef

define Package/luci-app-gozar/description
  Configuration and status pages for Gozar in LuCI.
endef

define Package/luci-app-gozar/install
	$(INSTALL_DIR) $(1)/usr/lib/lua/luci/controller
	$(INSTALL_DATA) ./luci/controller/gozar.lua $(1)/usr/lib/lua/luci/controller/
	$(INSTALL_DIR) $(1)/usr/lib/lua/luci/model/cbi
	$(INSTALL_DATA) ./luci/model/cbi/gozar.lua $(1)/usr/lib/lua/luci/model/cbi/
	$(INSTALL_DIR) $(1)/usr/lib/lua/luci/view/gozar
	$(INSTALL_DATA) ./luci/view/gozar/status.htm $(1)/usr/lib/lua/luci/view/gozar/
	$(INSTALL_DIR) $(1)/usr/share/rpcd/acl.d
	$(INSTALL_DATA) ./luci/acl/luci-app-gozar.json $(1)/usr/share/rpcd/acl.d/
endef

$(eval $(call BuildPackage,gozar))
$(eval $(call BuildPackage,luci-app-gozar))
