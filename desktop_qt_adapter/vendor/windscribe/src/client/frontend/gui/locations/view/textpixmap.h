#pragma once

#include <QFont>
#include <QPainter>

#include <QHash>

namespace gui_locations {

// Retain text and logical metrics, not a bitmap rasterized for another screen.
class TextPixmap
{
public:
    TextPixmap() {}
    explicit TextPixmap(const QString &text, const QFont &font);
    void draw(int x, int y, QPainter *painter) const;
    int width() const { return size_.width(); }
    int height() const { return size_.height(); }
    bool isNull() const { return text_.isEmpty(); }
    QFont font() const { return font_; }
    QString text() const { return text_; }

private:
    QFont font_;
    QSize size_{0, 0};
    QString text_;
};

// convenient for managing multiple text pixmaps
class TextPixmaps
{
public:
    TextPixmaps() {}
    void add(int id, const QString &text, const QFont &font);
    void updateIfTextChanged(int id, const QString &text, const QFont &font);
    TextPixmap pixmap(int id) const;

private:
    QHash<int, TextPixmap> pixmaps_;
};


} // namespace gui_locations

