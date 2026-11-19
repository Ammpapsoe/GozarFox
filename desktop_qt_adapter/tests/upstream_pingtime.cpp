// The upstream project supplies QMetaType via its build environment. Include it
// explicitly in this isolated test target before compiling the original source.
#include <QMetaType>
#include "types/pingtime.cpp"
