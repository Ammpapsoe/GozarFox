// SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
#pragma once

#include <QDateTime>
#include <QJsonObject>
#include <QLocale>
#include <QString>
#include "types/connectstate.h"

struct GozarTelemetry {
    QString status, rates, details;
};

// Existing 1.19.0 rates are TUN counters measured in bytes/second. Proxy modes
// don't publish equivalent byte counters; showing zero there would mislead.
inline GozarTelemetry gozarTelemetry(const QJsonObject &state, CONNECT_STATE visual, bool fa,
                                      qint64 now = QDateTime::currentSecsSinceEpoch()) {
    const auto text = [fa](const char *en, const char *persian) { return QString::fromUtf8(fa ? persian : en); };
    if (state.isEmpty()) return {text("Waiting for core", "در انتظار موتور"), {}, {}};
    if (visual == CONNECT_STATE_CONNECTING) return {text("Connecting…", "در حال اتصال…"), {}, {}};
    if (visual == CONNECT_STATE_DISCONNECTING) return {text("Disconnecting…", "در حال قطع اتصال…"), {}, {}};
    if (!state.value(QStringLiteral("live")).toBool()) return {text("Disconnected", "قطع اتصال"), {}, {}};
    GozarTelemetry out{text("Connected", "متصل"), {}, {}};
    const bool tunnel = state.value(QStringLiteral("mode")).toString() == QLatin1String("tun");
    const auto formatRate = [](const QJsonValue &value) {
        if (!value.isDouble() || value.toDouble() < 0) return QStringLiteral("—");
        double speed = value.toDouble();
        const char *units[] = {"B/s", "KiB/s", "MiB/s", "GiB/s"};
        int index = 0;
        while (speed >= 1024 && index < 3) { speed /= 1024; ++index; }
        return QStringLiteral("%1 %2").arg(speed, 0, 'f', index ? 1 : 0).arg(QString::fromLatin1(units[index]));
    };
    if (tunnel) {
        // Each segment is LTR to keep units, direction arrows and numbers in
        // their display order even with a Persian system language.
        out.rates = QStringLiteral("↓ %1      ↑ %2").arg(formatRate(state.value(QStringLiteral("rate_down"))),
                                                          formatRate(state.value(QStringLiteral("rate_up"))));
    }
    const qint64 since = state.value(QStringLiteral("since")).toInteger();
    if (since > 0 && since <= now) {
        const auto elapsed = now - since;
        out.details = QStringLiteral("%1:%2:%3").arg(elapsed / 3600, 2, 10, QLatin1Char('0'))
            .arg(elapsed / 60 % 60, 2, 10, QLatin1Char('0')).arg(elapsed % 60, 2, 10, QLatin1Char('0'));
    }
    if (tunnel) {
        const auto ip = state.value(QStringLiteral("exit")).toString();
        if (!ip.isEmpty()) out.details += (out.details.isEmpty() ? QString() : QStringLiteral("     ·     ")) + ip;
    }
    return out;
}
