import { Node } from 'cc';
import { art, artRegion, characterFallback, managedCharacterKey } from './Art';
import { TEAM_POSE_REGIONS } from './TeamPoseRegions';

export type TeamPose = 'give' | 'receive' | 'worry' | 'celebrate';

/** Cell 8 is the robot: never reduce robot avatars modulo the eight human identities. */
export function teamPose(parent: Node, pose: TeamPose, avatar: number,
    x: number, y: number, width: number, height: number, mirror = false): Node | null {
    const remote = managedCharacterKey(avatar, pose);
    if (remote) {
        const image = art(parent, remote, x, y, width, height);
        if (image && mirror) { image.setPosition(image.position.x + width, image.position.y); image.setScale(-1, 1, 1); }
        return image;
    }
    const cell = avatar === 8 ? 8 : characterFallback(avatar);
    return artRegion(parent, 'team_pose_' + pose, cell, 3, 3, x, y, width, height, mirror,
        TEAM_POSE_REGIONS['team_pose_' + pose]?.[cell]);
}
