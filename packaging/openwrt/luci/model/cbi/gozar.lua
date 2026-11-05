local m, s, o

m = Map("gozar", translate("Gozar"),
	translate("Sends this router's traffic through a Firefox Private Network proxy."))

s = m:section(TypedSection, "gozar", translate("Account"))
s.anonymous = true
s.addremove = false

o = s:option(Flag, "enabled", translate("Enable"))
o.default = "0"
o.rmempty = false

o = s:option(Value, "email", translate("Mozilla account"))
o.datatype = "email"
o.placeholder = "name@example.com"

o = s:option(Value, "password", translate("Password"))
o.password = true

o = s:option(Value, "code", translate("Two-factor code"))
o.datatype = "string"
o.placeholder = translate("only when the account has two-factor login")

o = s:option(Value, "server", translate("Server code"))
o.datatype = "string"
o.placeholder = translate("automatic")

local state = s:option(DummyValue, "_state", translate("State"))
state.rawhtml = true

function state.cfgvalue()
	if luci.sys.call("pidof gozar-core >/dev/null 2>&1") == 0 then
		return '<span style="color:#2e7d32">' .. translate("running") .. "</span>"
	end
	return '<span style="color:#c62828">' .. translate("stopped") .. "</span>"
end

local link = s:option(DummyValue, "_link", translate("Details"))
link.rawhtml = true

function link.cfgvalue()
	return '<a href="' .. luci.dispatcher.build_url("admin", "services", "gozar", "status")
		.. '">' .. translate("status and log") .. "</a>"
end

return m
