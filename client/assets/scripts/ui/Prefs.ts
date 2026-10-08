/** 本机偏好（存 localStorage，微信小游戏与浏览器通用）：目前只有快速动画。读写失败时只在本次运行内生效。 */
import { sys } from 'cc';
import { fastAnim, setFastAnim } from '../core/Theme';

const FAST_KEY = 'millionnaire.fastAnim';

/** 启动时恢复偏好。 */
export function loadPrefs(): void {
    try {
        setFastAnim(sys.localStorage.getItem(FAST_KEY) === '1');
    } catch {
        // 读不到就用默认（正常速度）
    }
}

export function toggleFastAnim(): boolean {
    const on = !fastAnim();
    setFastAnim(on);
    try {
        sys.localStorage.setItem(FAST_KEY, on ? '1' : '0');
    } catch {
        // 存不了就只在本次运行内生效
    }
    return on;
}
