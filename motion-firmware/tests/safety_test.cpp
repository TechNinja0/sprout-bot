#include "safety.hpp"
#include <cassert>
#include <limits>
using namespace family_robot;
int main() {
    SafetyController s;
    assert(!s.accept({1,0,500,0,0},0));
    assert(!s.enable(false,0));assert(s.enable(true,0));
    assert(s.accept({1,0,500,20,10},0));assert(s.tick(499).powered);assert(!s.tick(500).powered);
    assert(!s.accept({1,500,500,0,0},500)); // 重放
    assert(!s.accept({2,501,500,0,0},500)); // 不接受未来接收时间
    assert(!s.accept({2,0,500,0,0},500)); // 过期
    assert(!s.accept({2,500,501,0,0},500));
    assert(!s.accept({2,500,500,90,0},500));
    assert(!s.accept({2,500,500,std::numeric_limits<float>::quiet_NaN(),0},500));
    assert(s.accept({2,500,500,-45,25},500));assert(!s.tick(501,true).powered);
    assert(!s.enable(true,502));assert(!s.clear_emergency(false));assert(s.clear_emergency(true));
    assert(!s.accept({3,502,500,0,0},502));assert(s.enable(true,502));assert(s.accept({3,502,500,0,0},502));
    assert(!s.tick(501).powered); // 单调时钟回退锁定急停
    assert(s.clear_emergency(true));assert(s.enable(true,600));assert(s.accept({4,600,500,0,0},600));
    assert(!s.tick(601,false,true).powered);
}
