// Non-visual test-only substitute: the real app must use its own tooltip service.
#include "tooltips/tooltipcontroller.h"
#include "tooltips/tooltiputil.h"

TooltipController::TooltipController() = default;
void TooltipController::hideAllTooltips() {}
void TooltipController::showTooltipBasic(TooltipInfo) {}
void TooltipController::showTooltipDescriptive(TooltipInfo) {}
void TooltipController::hideTooltip(TooltipId) {}
void TooltipUtil::getFirewallBlockedTooltipInfo(QString *title, QString *desc) { title->clear(); desc->clear(); }
void TooltipUtil::getFirewallAlwaysOnTooltipInfo(QString *title, QString *desc) { title->clear(); desc->clear(); }
