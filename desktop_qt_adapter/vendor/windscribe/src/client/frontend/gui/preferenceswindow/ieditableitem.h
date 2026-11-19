#pragma once

namespace PreferencesWindow {

class IEditableItem
{
public:
    virtual ~IEditableItem() = default;

    virtual bool isInEditMode() const = 0;
    virtual void save() = 0;
    virtual void discard() = 0;
};

} // namespace PreferencesWindow
