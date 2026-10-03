package com.wenxian.demo.dm;

import static com.wenxian.demo.dm.Protocol.*;

/** DM生成边界。输入是确认状态和原行动，输出仍是待校验提议，不能在此修改存档。 */
interface DmGateway {
    ModelResult play(Save before,ActionRequest request);
    default AdventureProtocol.Outcome playAdventure(Save before,ActionRequest request){throw new Fault(503,"连续场景的DM尚未启用");}
    boolean ready();
}
