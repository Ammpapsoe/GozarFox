#pragma once
#include <QFileInfo>
#include <QString>
#define SAFE_DELETE(pointer) do { delete (pointer); (pointer) = nullptr; } while (false)
namespace Utils {
inline bool isFullyQualifiedPath(const QString &path) { return QFileInfo(path).isAbsolute(); }
// Declaration-only: needed when compiling upstream NetworkInterface headers.
// The compile-only window-header check does not link or call these methods.
QString toBase64(const QString &value);
QString fromBase64(const QString &value);
}
