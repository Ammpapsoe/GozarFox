#pragma once
#include <QFileInfo>
#include <QString>
#define SAFE_DELETE(pointer) do { delete (pointer); (pointer) = nullptr; } while (false)
namespace Utils {
inline bool isFullyQualifiedPath(const QString &path) { return QFileInfo(path).isAbsolute(); }
inline int generateIntegerRandom(const int &min, const int &max) { return min + (max - min) / 2; }
// Declaration-only: needed when compiling upstream NetworkInterface headers.
// The compile-only window-header check does not link or call these methods.
QString toBase64(const QString &value);
QString fromBase64(const QString &value);
}
