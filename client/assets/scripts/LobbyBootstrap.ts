import { _decorator, Camera, Canvas, Color, Component, Graphics, Label, Layers, Node, UITransform, Vec3, view, ResolutionPolicy } from 'cc';
const { ccclass } = _decorator;

/** 本地启动大厅。按钮只反馈状态，尚未调用后端。 */
@ccclass('LobbyBootstrap')
export class LobbyBootstrap extends Component {
    private status!: Label;

    onLoad(): void {
        view.setDesignResolutionSize(720, 1280, ResolutionPolicy.SHOW_ALL);
        const canvasNode = new Node('Canvas');
        canvasNode.layer = Layers.Enum.UI_2D;
        this.node.addChild(canvasNode);
        canvasNode.addComponent(UITransform).setContentSize(720, 1280);
        const canvas = canvasNode.addComponent(Canvas);
        const cameraNode = new Node('UICamera');
        canvasNode.addChild(cameraNode);
        cameraNode.setPosition(new Vec3(0, 0, 1000));
        const camera = cameraNode.addComponent(Camera);
        camera.projection = Camera.ProjectionType.ORTHO;
        camera.orthoHeight = 640;
        camera.near = 0.1;
        camera.far = 2000;
        camera.visibility = Layers.Enum.UI_2D;
        camera.clearFlags = Camera.ClearFlag.SOLID_COLOR;
        camera.clearColor = new Color(238, 246, 240, 255);
        canvas.cameraComponent = camera;
        canvas.alignCanvasWithScreen = true;

        this.panel(canvasNode, 'Background', 0, 0, 720, 1280, new Color(238, 246, 240));
        this.text(canvasNode, 'Title', '好友桌游', 0, 385, 54, new Color(32, 68, 70));
        this.text(canvasNode, 'Subtitle', '和朋友一起，开启一局新旅程', 0, 308, 25, new Color(86, 109, 110));
        this.panel(canvasNode, 'BoardPreview', 0, 96, 560, 260, new Color(212, 231, 221));
        this.text(canvasNode, 'BoardTitle', '好友房间 · 2～8人', 0, 140, 34, new Color(32, 68, 70));
        this.text(canvasNode, 'MapSummary', '30格 / 50格地图', 0, 78, 27, new Color(66, 91, 89));
        this.button(canvasNode, 'CreateRoom', '创建房间', -122, new Color(249, 185, 70), () => {
            this.status.string = '创建房间：等待接入本地后端';
        });
        this.button(canvasNode, 'JoinRoom', '房间号加入', -252, new Color(91, 170, 222), () => {
            this.status.string = '房间号加入：等待接入本地后端';
        });
        this.status = this.text(canvasNode, 'Status', '客户端已启动 · 当前为本地界面预览', 0, -385, 23, new Color(86, 109, 110));
        this.text(canvasNode, 'Footer', '联机、微信登录与实时语音尚未接入', 0, -450, 20, new Color(86, 109, 110));
    }

    private panel(parent: Node, name: string, x: number, y: number, width: number, height: number, color: Color): Node {
        const node = new Node(name);
        node.layer = Layers.Enum.UI_2D;
        parent.addChild(node);
        node.setPosition(x, y);
        node.addComponent(UITransform).setContentSize(width, height);
        const graphics = node.addComponent(Graphics);
        graphics.fillColor = color;
        graphics.roundRect(-width / 2, -height / 2, width, height, 26);
        graphics.fill();
        return node;
    }

    private text(parent: Node, name: string, content: string, x: number, y: number, size: number, color: Color): Label {
        const node = new Node(name);
        node.layer = Layers.Enum.UI_2D;
        parent.addChild(node);
        node.setPosition(x, y);
        node.addComponent(UITransform).setContentSize(650, 70);
        const label = node.addComponent(Label);
        label.string = content;
        label.fontSize = size;
        label.lineHeight = size + 10;
        label.color = color;
        label.horizontalAlign = Label.HorizontalAlign.CENTER;
        label.verticalAlign = Label.VerticalAlign.CENTER;
        return label;
    }

    private button(parent: Node, name: string, title: string, y: number, color: Color, action: () => void): void {
        const button = this.panel(parent, name, 0, y, 560, 104, color);
        const caption = this.text(button, name + 'Label', title, 0, 0, 34, new Color(25, 53, 64));
        caption.node.getComponent(UITransform)!.setContentSize(530, 70);
        button.on(Node.EventType.TOUCH_END, action, this);
    }
}
