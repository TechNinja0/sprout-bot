#pragma once
#include <cstdint>
#include <algorithm>

namespace family_robot {
// 硬件适配器必须独占PWM输出。此核心只决定安全目标，不直接驱动任何设备。
struct Command { uint64_t sequence; uint64_t received_ms; uint32_t ttl_ms; float pan; float tilt; };
struct Target { float pan=0; float tilt=0; bool powered=false; };
class SafetyController {
    uint64_t last_sequence_=0, deadline_=0, last_tick_=0;
    bool enabled_=false, estop_=false;
    Target target_{};
public:
    bool enable(bool physical_confirmation, uint64_t now) {
        if (!physical_confirmation || estop_) return false;
        enabled_=true;last_tick_=now;deadline_=now;return true;
    }
    void emergency_stop() { estop_=true;enabled_=false;target_={}; }
    bool clear_emergency(bool physical_confirmation) {
        if (!physical_confirmation) return false;
        estop_=false;return true; // 解除急停不重新上电。
    }
    bool accept(const Command& c,uint64_t now) {
        if (!enabled_ || estop_ || c.sequence<=last_sequence_ || c.ttl_ms==0 || c.ttl_ms>500) return false;
        if (now<c.received_ms || now-c.received_ms>=c.ttl_ms) return false;
        // NaN亦拒绝；夹限不能代替确认有效数值。
        if (!(c.pan>=-45 && c.pan<=45 && c.tilt>=-20 && c.tilt<=25)) return false;
        last_sequence_=c.sequence;deadline_=c.received_ms+c.ttl_ms;
        target_={c.pan,c.tilt,true};return true;
    }
    Target tick(uint64_t now,bool overcurrent=false,bool limit_switch=false) {
        if (now<last_tick_ || overcurrent || limit_switch) emergency_stop();
        last_tick_=now;
        if (!enabled_ || estop_ || now>=deadline_) target_={};
        return target_;
    }
};
}
