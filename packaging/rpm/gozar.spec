%global debug_package %{nil}
%global __strip /bin/true
%global __os_install_post %{nil}

Name:           gozar
Version:        %{gozar_version}
Release:        1%{?gozar_dist}
Summary:        Firefox Private Network client
License:        AGPL-3.0-or-later
URL:            https://github.com/bardia049b-stack/Gozar
Source0:        %{name}-%{version}.tar.xz
BuildArch:      x86_64
Requires:       gtk3
Requires:       glib2
Requires:       cairo
Requires:       cairo-gobject
Requires:       pango
Requires:       harfbuzz
Requires:       gdk-pixbuf2
Requires:       atk
Requires:       zlib
Requires:       libstdc++
Requires:       iproute
Requires:       glibc >= 2.34

%description
Gozar connects the desktop to the Firefox Private Network proxy. The package
carries the Flutter application and the separate Go core that builds the tunnel.
The tunnel creates a TUN interface and rewrites the routing table, which needs
administrator rights, so start Gozar with sudo: sudo gozar

%prep
%setup -q -c -T -n %{name}-%{version}

%build

%install
mkdir -p %{buildroot}%{_libdir}/gozar
cp -a %{_sourcedir}/bundle/. %{buildroot}%{_libdir}/gozar/
mkdir -p %{buildroot}%{_bindir} %{buildroot}%{_datadir}/applications %{buildroot}%{_datadir}/pixmaps
install -m 644 %{_sourcedir}/gozar.desktop %{buildroot}%{_datadir}/applications/gozar.desktop
install -m 644 %{_sourcedir}/gozar.png %{buildroot}%{_datadir}/pixmaps/gozar.png
printf '#!/bin/sh\nexec %{_libdir}/gozar/gozar "$@"\n' > %{buildroot}%{_bindir}/gozar
chmod 755 %{buildroot}%{_bindir}/gozar

%files
%{_libdir}/gozar
%{_bindir}/gozar
%{_datadir}/applications/gozar.desktop
%{_datadir}/pixmaps/gozar.png

%changelog
* Tue Nov 04 2026 Gozar contributors <noreply@github.com> - %{gozar_version}-1
- Package the desktop application and its Go core.
