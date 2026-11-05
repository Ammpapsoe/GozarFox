module("luci.controller.gozar", package.seeall)

function index()
	if not nixio.fs.access("/etc/config/gozar") then
		return
	end

	entry({"admin", "services", "gozar"}, cbi("gozar"), _("Gozar"), 60).dependent = true
	entry({"admin", "services", "gozar", "status"}, template("gozar/status")).leaf = true
end
