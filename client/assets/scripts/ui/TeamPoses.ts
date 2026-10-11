import { Node } from 'cc';
import { artRegion } from './Art';
import { TEAM_POSE_REGIONS } from './TeamPoseRegions';

export type TeamPose = 'give' | 'receive' | 'worry' | 'celebrate';

/** Cell 8 is the robot: never reduce robot avatars modulo the eight human identities. */
export function teamPose(parent: Node, pose: TeamPose, avatar: number,
    x: number, y: number, width: number, height: number, mirror = false): Node | null {
    const cell = Number.isInteger(avatar) && avatar >= 0 && avatar <= 8 ? avatar : 0;
    return artRegion(parent, 'team_pose_' + pose, cell, 3, 3, x, y, width, height, mirror,
        TEAM_POSE_REGIONS['team_pose_' + pose]?.[cell]);
}
