/** One notification per actionable turn, even if the board is rebuilt or revisited. */
export class TurnReminder {
    private seen = new Set<string>();
    take(key: string, actionable: boolean, visible: boolean): boolean {
        if (!actionable || !visible || this.seen.has(key)) return false;
        this.seen.add(key);
        if (this.seen.size > 256) this.seen.delete(this.seen.values().next().value!);
        return true;
    }
}
