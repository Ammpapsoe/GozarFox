#include "textpixmap.h"
#include "utils/ws_assert.h"

namespace gui_locations {

TextPixmap::TextPixmap(const QString &text, const QFont &font) : font_(font), text_(text)
{
    if (!text.isEmpty()) {
        const QFontMetrics fm(font);
        size_ = QSize(fm.horizontalAdvance(text), fm.boundingRect(text).height());
    }
}

void TextPixmap::draw(int x, int y, QPainter *painter) const
{
    if (isNull()) return;
    painter->save();
    painter->setRenderHint(QPainter::TextAntialiasing);
    painter->setPen(Qt::white);
    painter->setFont(font_);
    painter->drawText(QRect(QPoint(x, y), size_), Qt::AlignLeft, text_);
    painter->restore();
}

void TextPixmaps::add(int id, const QString &text, const QFont &font)
{
    WS_ASSERT(!pixmaps_.contains(id));
    pixmaps_[id] = TextPixmap(text, font);
}

void TextPixmaps::updateIfTextChanged(int id, const QString &text, const QFont &font)
{
    WS_ASSERT(pixmaps_.contains(id));
    auto it = pixmaps_.find(id);
    if (it != pixmaps_.end())
    {
        if (it.value().text() != text || it.value().font() != font)
        {
            pixmaps_[id] = TextPixmap(text, font);
        }
    }
}

TextPixmap TextPixmaps::pixmap(int id) const
{
    WS_ASSERT(pixmaps_.contains(id));
    return pixmaps_[id];
}

} // namespace gui_locations
