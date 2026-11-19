#pragma once
#include <QFileInfo>
#include <QString>
#define SAFE_DELETE(pointer) do { delete (pointer); (pointer) = nullptr; } while (false)
namespace Utils {
inline bool isFullyQualifiedPath(const QString &path) { return QFileInfo(path).isAbsolute(); }
}
