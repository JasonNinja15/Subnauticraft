using System;
using System.Collections;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Net.Sockets;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using BepInEx;
using HarmonyLib;
using UnityEngine;

namespace MinecraftLink
{
    /// <summary>
    /// The Subnautica half of the Minecraft-Subnautica link: shared health, hunger, oxygen, death,
    /// movement (Minecraft moves the player) and collision (Minecraft asks Subnautica what is in the way).
    ///
    /// The Minecraft mod waits on a port on this computer. This mod connects to it and the two
    /// swap short lines of text such as "HEALTH 0.75" or "DEATH". Health is a fraction of full
    /// health, because the games count health differently (100 here, 20 in Minecraft).
    ///
    /// The network waits happen on a background thread. Unity only allows game objects to be
    /// touched from the main thread, so that thread just drops lines into a queue, and Update()
    /// (which Unity calls once per frame on the main thread) picks them up.
    /// </summary>
    [BepInPlugin("com.example.minecraftlink", "Minecraft Link", "2.19.2")]
    public class MinecraftLinkPlugin : BaseUnityPlugin
    {
        /// <summary>The port both mods use. It must match the number in the Minecraft mod.</summary>
        private const int Port = 25599;

        /// <summary>How long to wait before trying to connect again, in milliseconds.</summary>
        private const int RetryDelayMs = 2000;

        private const string Connected = "#CONNECTED";
        private const string Disconnected = "#DISCONNECTED";

        private readonly ConcurrentQueue<string> incoming = new ConcurrentQueue<string>();
        private readonly object writeLock = new object();
        private StreamWriter writer;
        private volatile bool running;

        /// <summary>Health changes smaller than this (in Subnautica health points) are ignored.</summary>
        private const float SmallestChange = 0.05f;

        /// <summary>
        /// The health both games last agreed on, or -1 when there is no player to watch. Each
        /// frame the player's real health is compared with this; a difference means something
        /// in Subnautica changed it, so Minecraft needs to be told.
        /// </summary>
        private float lastHealth = -1f;

        /// <summary>Subnautica's food and water bars are full at this value.</summary>
        private const float FullStat = 100f;

        /// <summary>Food rising by more than this (in Subnautica food points) counts as eating.</summary>
        private const float SmallestMeal = 0.25f;

        /// <summary>Oxygen has to move this far (as a fraction of a full supply) before Minecraft is told.</summary>
        private const float SmallestOxygenChange = 0.02f;

        /// <summary>
        /// The food level Minecraft's hunger bar says we should have (in Subnautica food points),
        /// or -1 until Minecraft has told us.
        /// </summary>
        private float lastFood = -1f;

        /// <summary>The oxygen fraction last sent to Minecraft, or -1 for "send it again".</summary>
        private float lastOxygen = -1f;

        // ---- Movement: Minecraft moves the player; this side sends controls and follows ----

        /// <summary>Seconds between control messages. 1/60 is plenty for 20 Minecraft ticks a second.</summary>
        private const float InputInterval = 1f / 60f;

        /// <summary>How quickly the Subnautica character closes the gap to Minecraft's position. Higher is snappier.</summary>
        private const float FollowSharpness = 20f;

        /// <summary>A gap bigger than this (in metres) is jumped in one go instead of glided across.</summary>
        private const float SnapDistance = 5f;

        /// <summary>If Minecraft hasn't sent a position for this long, stop following.</summary>
        private const float TargetTimeout = 1f;

        /// <summary>When the next control message is due.</summary>
        private float nextInputTime;

        /// <summary>True when Minecraft needs telling where to start from (on linking, and after respawning).</summary>
        private bool spawnPending;

        /// <summary>True from dying until coming back, to spot the moment of respawn.</summary>
        private bool wasDead;

        /// <summary>Where Minecraft says the player is (already in Subnautica's coordinates), and when it said so.
        /// It is the position of the EYES, so that both games look out from exactly the same point.</summary>
        private Vector3 target;
        private float targetTime = -100f;

        /// <summary>Where Minecraft said the eyes were the time before, and how high the eyes are above the feet.</summary>
        private Vector3 previousTarget;
        private float targetEyeHeight = MinecraftEyeHeight;

        /// <summary>
        /// The gap between where the eyes were being drawn and where the newest glide starts,
        /// closed gradually so the picture never jumps. See LateUpdate.
        /// </summary>
        private Vector3 followOffset;

        /// <summary>Seconds between Minecraft's position reports: one tick.</summary>
        private const float TickSeconds = 0.05f;

        /// <summary>Minecraft's eye height above the feet when standing, in metres.</summary>
        private const float MinecraftEyeHeight = 1.62f;

        /// <summary>
        /// If the character is this far (in metres) from where this mod last put it, Subnautica
        /// moved it by itself (a hatch, a ladder, a respawn), and Minecraft must catch up.
        /// </summary>
        private const float WarpDistance = 3f;

        /// <summary>Where this mod last put the character, to spot Subnautica moving it by itself.</summary>
        private Vector3 lastPlaced;

        /// <summary>Where this mod last put the eyes (the camera).</summary>
        private Vector3 lastEyes;
        private bool hasPlaced;

        /// <summary>True while Subnautica is playing one of its own animations with the player (climbing through a hatch, say).</summary>
        private bool wasCinematic;

        // After telling Minecraft to start from a new spot, positions from the old spot are
        // still on their way here. They are ignored until one arrives near the new spot.
        private bool waitingForMinecraft;
        private Vector3 waitSpot;
        private float waitUntil;

        // ---- Keys --------------------------------------------------------------------------
        // Sprint and sneak use Minecraft's keys (Control and Shift) rather than whatever they
        // are set to in Subnautica, so they are read straight from Windows.

        [DllImport("user32.dll")]
        private static extern short GetAsyncKeyState(int key);

        /// <summary>Windows' numbers for the Shift and Control keys (either side of the keyboard).</summary>
        private const int ShiftKey = 0x10;
        private const int ControlKey = 0x11;
        private const int LeftMouseButton = 0x01;
        private const int RightMouseButton = 0x02;
        private const int MiddleMouseButton = 0x04;

        /// <summary>The "1" key. The other number keys follow it in order.</summary>
        private const int FirstNumberKey = 0x31;

        /// <summary>Which number keys were down last frame, to spot the moment one is pressed.</summary>
        private readonly bool[] numberKeyWasDown = new bool[9];

        // Subnautica's own "next tool" and "previous tool" controls (the scroll wheel, unless
        // you changed it). Looked up by name the first time they are needed.
        private bool cycleLookupDone;
        private bool cycleFound;
        private GameInput.Button cycleNext;
        private GameInput.Button cyclePrev;

        [StructLayout(LayoutKind.Sequential)]
        private struct ScreenPoint
        {
            public int x;
            public int y;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct ScreenRect
        {
            public int left;
            public int top;
            public int right;
            public int bottom;
        }

        [DllImport("user32.dll")]
        private static extern bool GetCursorPos(out ScreenPoint point);

        [DllImport("user32.dll")]
        private static extern bool ScreenToClient(IntPtr window, ref ScreenPoint point);

        [DllImport("user32.dll")]
        private static extern bool GetClientRect(IntPtr window, out ScreenRect rect);

        [DllImport("user32.dll")]
        private static extern IntPtr GetActiveWindow();

        private static bool KeyHeld(int key)
        {
            // The top bit is set while the key is down.
            return (GetAsyncKeyState(key) & 0x8000) != 0;
        }

        // ---- Minecraft's HUD, drawn over Subnautica ------------------------------------------
        //
        // Minecraft draws its HUD (hearts, hunger, bubbles, hotbar, chat) onto a see-through
        // picture and writes the pixels into a file both games have open as shared memory.
        // This side copies the newest picture into a texture and draws it over the whole screen.
        // Subnautica's own health, food, water and oxygen bars are hidden while it shows.
        //
        // File layout (all numbers 4 bytes): 0 marker, 4 width, 8 height, 12 frame number,
        // 16 which of the two picture slots is complete; slot 0 starts at byte 64, slot 1 after it.

        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern IntPtr CreateFileW(string name, uint access, uint share, IntPtr security, uint creation, uint flags, IntPtr template);

        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern IntPtr CreateFileMappingW(IntPtr file, IntPtr security, uint protect, uint sizeHigh, uint sizeLow, string name);

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern IntPtr MapViewOfFile(IntPtr mapping, uint access, uint offsetHigh, uint offsetLow, UIntPtr bytes);

        [DllImport("kernel32.dll")]
        private static extern bool UnmapViewOfFile(IntPtr address);

        [DllImport("kernel32.dll")]
        private static extern bool CloseHandle(IntPtr handle);

        private const int OverlayMarker = 0x4B4C4E53;
        private const int OverlayHeaderBytes = 64;
        private const int OverlayMaxWidth = 3840;
        private const int OverlayMaxHeight = 2160;
        private const int OverlaySlotBytes = OverlayMaxWidth * OverlayMaxHeight * 4;
        private const long OverlayFileBytes = OverlayHeaderBytes + 2L * OverlaySlotBytes;

        /// <summary>The names of Subnautica's bars that Minecraft's HUD replaces. The last is its tool bar.</summary>
        private static readonly string[] ReplacedBars = { "uGUI_HealthBar", "uGUI_FoodBar", "uGUI_WaterBar", "uGUI_OxygenBar", "uGUI_QuickSlots", "uGUI_SeamothHUD", "uGUI_ExosuitHUD" };

        /// <summary>This plugin, for the checks added to Subnautica's own code (which have nothing else to reach it by).</summary>
        private static MinecraftLinkPlugin self;

        /// <summary>Subnautica's tool bar, which doubles as a vehicle's module bar (see TendModuleBar), and where it normally sits.</summary>
        private Transform moduleBar;
        private Vector3 moduleBarHome;
        private bool moduleBarMoved;

        /// <summary>True while connected to Minecraft.</summary>
        private bool linked;

        /// <summary>Where Minecraft said the shared file is.</summary>
        private string overlayPath;

        // The shared file, once open: the file itself, its mapping into memory, and the address of that memory.
        private IntPtr overlayFile = IntPtr.Zero;
        private IntPtr overlayMapping = IntPtr.Zero;
        private IntPtr overlayMemory = IntPtr.Zero;
        private float nextOverlayOpenTry;

        private Texture2D overlayTexture;
        private int overlayFrame = -1;

        /// <summary>When a new picture last arrived. The overlay is only drawn while they keep coming.</summary>
        private float overlayFrameTime = -100f;

        /// <summary>The body parts and tools of Subnautica's character that have been made invisible.</summary>
        private readonly HashSet<Renderer> hiddenBody = new HashSet<Renderer>();
        private readonly List<Renderer> bodyScratch = new List<Renderer>();

        /// <summary>The parts of Subnautica's HUD that have been shrunk to nothing, and the size each was before.</summary>
        private readonly Dictionary<Transform, Vector3> hiddenBars = new Dictionary<Transform, Vector3>();

        /// <summary>True while Subnautica's PDA is open. Minecraft's hand and HUD step aside for it.</summary>
        private bool pdaOpen;
        private MethodInfo getPda;
        private bool getPdaLookupDone;

        /// <summary>
        /// True while keys are being typed into Minecraft's chat box. Subnautica's own controls
        /// are switched off for the duration (see BlockSubnauticaToolKeys).
        /// </summary>
        private static bool typing;

        /// <summary>
        /// True while a Minecraft screen (the inventory, or the chat box) is open. Minecraft says
        /// when ("MCSCREEN 1" / "MCSCREEN 0"). While it is, the mouse pointer is set free to
        /// click on that screen, and Subnautica's own controls are switched off.
        /// </summary>
        private static bool screenOpen;

        /// <summary>True while this mod is the one holding Subnautica's hunger and thirst still (see Update, step 3).</summary>
        private bool frozeHunger;

        // The pointer position and mouse buttons last sent to Minecraft, to send only changes.
        private float sentPointerX = -1f;
        private float sentPointerY = -1f;
        private readonly bool[] sentButtons = new bool[2];

        /// <summary>
        /// A panel this mod draws over a Minecraft screen (in pixels from the window's top left
        /// corner), and whether it is showing. Mouse presses on it are not passed to Minecraft.
        /// </summary>
        private Rect menuPanel;
        private bool menuPanelShown;

        /// <summary>The mouse buttons whose current press began on that panel.</summary>
        private readonly bool[] panelButtons = new bool[2];

        private PropertyInfo lockCursorSetting;
        private bool lockCursorLookupDone;

        /// <summary>True while connected to Minecraft. A copy the damage check below can read.</summary>
        private static bool linkedNow;

        /// <summary>True while this mod itself is applying damage that Minecraft asked for.</summary>
        private static bool applyingMinecraftDamage;

        private static int damageNotes;
        private static BepInEx.Logging.ManualLogSource damageLog;

        /// <summary>The player's physics body, while this mod has taken it out of Unity's physics.</summary>
        private Rigidbody heldPlayerBody;

        // ---- Subnautica's tools, held through Minecraft items --------------------------------
        // Minecraft has a "token" item for each tool in Subnautica's inventory. This side sends
        // the list of tools ("TOOLS ..."), and Minecraft says which token is in hand ("EQUIP
        // name"). The real tool is then taken out here, kept invisible, and works as normal.

        /// <summary>The tool Minecraft says is in hand (Subnautica's internal name for it), or null.</summary>
        private string equippedTool;
        private bool dropKeyWasDown;

        /// <summary>Windows' number for the F key.</summary>
        private const int SwapKey = 0x46;
        private bool swapKeyWasDown;

        /// <summary>The list of tools last sent to Minecraft.</summary>
        private string sentTools;
        private float nextToolCheck;

        /// <summary>The lifepod's physics body, while this mod is holding it still (see HoldLifepodStill).</summary>
        private Rigidbody heldLifepod;
        private float nextLifepodCheck;

        /// <summary>
        /// True while Subnautica must not take tools out. Read by the small check that is slotted
        /// in front of Subnautica's own tool-selecting code (see BlockSubnauticaToolKeys).
        /// </summary>
        private static bool blockSubnauticaTools;
        private float nextBarCheck;

        private int sentScreenWidth;
        private int sentScreenHeight;

        // ---- Minecraft's blocks, built in Subnautica's world -----------------------------------
        //
        // Minecraft sends "BLOCK x y z colour" for each block it has and "BLOCK x y z air" for
        // one that has gone. Each block becomes a one-metre cube here, with a solid shape so
        // that the player (whose collision comes from this game) and creatures bump into it.

        /// <summary>How far, in metres, a block can be placed from the camera. Minecraft's own reach.</summary>
        private const float BlockReach = 4.5f * 1.25f;

        /// <summary>The cubes, by Minecraft position (packed into one number).</summary>
        private readonly Dictionary<long, GameObject> blocks = new Dictionary<long, GameObject>();

        /// <summary>One plain material (surface appearance) per colour, for blocks without a texture.</summary>
        private readonly Dictionary<int, Material> blockMaterials = new Dictionary<int, Material>();

        /// <summary>One textured material per tint. All of them show the block atlas.</summary>
        private readonly Dictionary<int, Material> texturedMaterials = new Dictionary<int, Material>();

        /// <summary>What one kind of block looks like: its shape, and the tint of each part of it.</summary>
        private class BlockModel
        {
            public Mesh mesh;
            public int[] tints;

            /// <summary>The faces as Minecraft described them, kept so the shape can be made again.</summary>
            public string quads = "";

            /// <summary>Its picture moves (fire, say), so the shape is remade whenever the atlas changes.</summary>
            public bool animated;

            /// <summary>It is drawn see-through (poured water).</summary>
            public bool seeThrough;

            /// <summary>Whether the shape was made with the atlas to hand (so see-through parts are cut out).</summary>
            public bool cutOut;

            /// <summary>Where each face's picture is on the atlas (left, top, right, bottom) and its tint. Worked out when first needed.</summary>
            public List<float[]> pictures;
        }

        // ---- Light from blocks ----------------------------------------------------------------
        //
        // "LIGHT id range strength colour flicker" says a kind of block gives off light. Every
        // block of that kind gets one of Unity's ordinary point lights at its middle, the same
        // sort Subnautica lights its own lamps with. Only the nearest few are switched on at a
        // time, so a wall of torches can't slow the game down.

        /// <summary>What each light-giving kind of block gives off: reach, strength, red, green, blue, flicker.</summary>
        private readonly Dictionary<int, float[]> blockLights = new Dictionary<int, float[]>();

        private class BlockLight
        {
            public Light light;
            public float strength;
            public bool flicker;
            public float seed;
        }

        private readonly List<BlockLight> lights = new List<BlockLight>();
        private float nextLightSort;

        /// <summary>The most block lights switched on at once.</summary>
        private const int MostLights = 24;

        // ---- Effects ---------------------------------------------------------------------------
        //
        // Bits of blocks are drawn by this mod itself: small squares showing a piece of the
        // block's own picture, thrown out and falling, as in Minecraft. Everything else is one
        // of Subnautica's own effects, borrowed from wherever the game keeps it.

        /// <summary>One burst of block bits: a handful of little squares, all drawn as one shape.</summary>
        private class Debris
        {
            public GameObject thing;
            public Mesh mesh;
            public Vector3[] places;
            public Vector3[] speeds;
            public float[] sizes;
            public Vector3[] corners;
            public float born;
            public float life;
            public bool inWater;
        }

        private readonly List<Debris> debris = new List<Debris>();
        private float lastBurstTime;
        private int burstsThisMoment;

        /// <summary>Subnautica's effects, once found: bubbles, a surface splash, the Seamoth's explosion, smoke, a warp swirl.</summary>
        private ParticleSystem bubblesEffect;
        private GameObject splashEffect;
        private GameObject blastEffect;
        private ParticleSystem smokeEffect;
        private GameObject warpEffect;
        private GameObject warpOutEffect;
        private bool effectsRequested;
        private readonly Dictionary<string, float> effectLastPlayed = new Dictionary<string, float>();

        // ---- Cracks on a block being broken ----------------------------------------------------

        /// <summary>Where the ten cracking pictures are on the atlas: left, top, right, bottom for each.</summary>
        private float[] crackSpots;
        private readonly Mesh[] crackMeshes = new Mesh[10];
        /// <summary>The cracks being shown: one set for each player breaking a block, by that player's number in Minecraft.</summary>
        private class Cracks
        {
            public GameObject thing;
            public long key = -1;
        }

        private readonly Dictionary<int, Cracks> cracks = new Dictionary<int, Cracks>();

        /// <summary>The number in the heading of the atlas file last loaded. Minecraft raises it with each new version.</summary>
        private int atlasNumber = -1;
        private readonly byte[] atlasHead = new byte[16];

        /// <summary>The models Minecraft has described so far, by its number for that kind of block.</summary>
        private readonly Dictionary<int, BlockModel> blockModels = new Dictionary<int, BlockModel>();

        /// <summary>One of Subnautica's own materials that leaves out see-through pixels, copied for blocks.</summary>
        private Material cutoutTemplate;
        private float nextTemplateSearch;
        private Texture2D flatBump;

        /// <summary>Minecraft's block atlas: every block texture side by side in one picture.</summary>
        private Texture2D blockAtlas;
        private string atlasPath;
        private float nextAtlasTry;

        /// <summary>The atlas's pixels (red, green, blue, opacity for each), kept to see which are see-through.</summary>
        private byte[] atlasPixels;
        private int atlasWidth;
        private int atlasHeight;

        /// <summary>A plain cube, for blocks Minecraft gave no model for (chests and the like).</summary>
        private Mesh plainCube;

        // ---- Minecraft's dropped items ---------------------------------------------------------
        //
        // Minecraft sends "ITEMMODEL kind scale colour quads" once for each kind of item, then
        // "ITEM id kind x y z" whenever a dropped item appears or moves and "ITEMGONE id" when
        // it is picked up. Each becomes a small slowly-turning copy here. They are only for
        // show: Minecraft decides where they are and when they are picked up. (It works out
        // where they land by asking this game, with the same SWEEP question the player uses.)

        /// <summary>What one kind of item looks like.</summary>
        private class ItemModel
        {
            public string quads;
            public float scale;
            public int colour;
            public Mesh mesh;
            public int[] tints;

            /// <summary>Whether the mesh was made with the atlas to hand (so see-through parts are cut out).</summary>
            public bool cutOut;
        }

        /// <summary>One dropped item being shown.</summary>
        private class ShownItem
        {
            public GameObject thing;
            public int kind;
            public Vector3 target;
            public float scale;
            public float turn;
            public bool dressedWithAtlas;

            /// <summary>-1 for a dropped item. For a projectile: 0 = faces the camera, 1 = points along its flight.</summary>
            public int style = -1;

            /// <summary>A projectile's speed, in metres a second, and when its position was last heard.</summary>
            public Vector3 speed;
            public float heardAt;
            public Vector3 heading = Vector3.forward;

            /// <summary>Its height last frame (to spot it crossing the sea's surface) and when it last left bubbles.</summary>
            public float lastHeight = float.NaN;
            public float nextBubbles;

            /// <summary>For lit TNT: how many Minecraft ticks its fuse had left when last heard, and when that was. -1 for none.</summary>
            public float fuse = -1f;
            public float fuseHeardAt;
            public bool flashing;
            public Material[] ownMaterials;
        }

        /// <summary>The plain white material lit TNT flashes to.</summary>
        private Material flashMaterial;

        private readonly Dictionary<int, ItemModel> itemModels = new Dictionary<int, ItemModel>();
        private readonly Dictionary<int, ShownItem> shownItems = new Dictionary<int, ShownItem>();
        private GameObject itemRoot;

        /// <summary>A plain cube centred on its middle, for items Minecraft gave no model for.</summary>
        private Mesh plainItemCube;

        /// <summary>A face is never cut into more than this many pieces along one side.</summary>
        private const int MostPiecesPerSide = 32;

        /// <summary>
        /// The six faces of a cube in Minecraft's coordinates, four corners each (x, y, z), going
        /// anticlockwise as seen from outside, which is how Minecraft lists its own faces.
        /// </summary>
        private static readonly float[] CubeFaces =
        {
            0, 0, 1,  0, 0, 0,  1, 0, 0,  1, 0, 1,   // bottom
            0, 1, 0,  0, 1, 1,  1, 1, 1,  1, 1, 0,   // top
            1, 1, 0,  1, 0, 0,  0, 0, 0,  0, 1, 0,   // north
            0, 1, 1,  0, 0, 1,  1, 0, 1,  1, 1, 1,   // south
            0, 1, 0,  0, 0, 0,  0, 0, 1,  0, 1, 1,   // west
            1, 1, 1,  1, 0, 1,  1, 0, 0,  1, 1, 0    // east
        };

        private GameObject blockRoot;
        private Shader blockShader;
        private bool blockShaderLookupDone;
        private Type skyApplierType;

        // ---- Water --------------------------------------------------------------------------
        // Minecraft's ocean void has no water in it. Instead, Minecraft treats everything below
        // sea level as water unless Subnautica says the player is somewhere dry (a base, the
        // lifepod, an alien building). Subnautica also says how fast the player swims here, so
        // Minecraft's swimming can be scaled to match.

        /// <summary>Used if the game's own swim speed can't be read. Subnautica's basic swim speed, in metres per second.</summary>
        private const float DefaultSwimSpeed = 5f;

        /// <summary>What Minecraft was last told: 1 dry, 0 not dry, -1 for "send it again".</summary>
        private int lastDry = -1;
        private int lastAboard = -1;
        private int lastNight = -1;
        private string lastBiome;

        /// <summary>What Subnautica's own beds skip: this many seconds of the game's day, over this many real ones.</summary>
        private const float SleepSkipsSeconds = 396f;
        private const float SleepTakesSeconds = 5f;

        /// <summary>The swim speed last sent to Minecraft, or -1 for "send it again".</summary>
        private float lastSwimSpeed = -1f;

        private float nextWaterCheck;

        // The game's swimming code, looked up by name the first time it is needed.
        private bool swimLookupDone;
        private Type swimMotorType;
        private FieldInfo swimSpeedField;
        private MethodInfo swimSpeedAdjuster;
        private readonly HashSet<string> warnedMissing = new HashSet<string>();

        // ---- Collision: Minecraft asks, Subnautica's physics answers ----
        //
        // Each time Minecraft is about to move the player it sends the player's box and the
        // movement it wants ("SWEEP"). This side slides that box through Subnautica's real
        // scenery, exactly as shaped, and replies with how far it got ("SWEPT").

        /// <summary>
        /// A thin margin, in metres. The box is tested slightly smaller than it is and stopped
        /// this far short, which keeps it from catching on surfaces it is already touching, and
        /// lets scenery that moves a little (the bobbing lifepod) push it instead of passing through.
        /// </summary>
        private const float Skin = 0.03f;

        /// <summary>How far short of a surface the box is stopped, in metres. See CastBox.</summary>
        private const float RestGap = 0.012f;

        /// <summary>A surface counts as floor if it is at most about 45 degrees from flat (this is the upward part of its facing direction).</summary>
        private const float FloorNormalY = 0.7f;

        /// <summary>Walking off a drop smaller than this (in metres) keeps the feet on the ground, so walking downhill is smooth.</summary>
        private const float SnapDownDistance = 0.4f;

        /// <summary>Which of Unity's collision layers the player collides with, and when that was last worked out.</summary>
        private int solidLayers;
        private float solidLayersTime = -100f;

        /// <summary>Scratch space for Unity to list what a moving box would hit.</summary>
        private readonly RaycastHit[] sweepHits = new RaycastHit[32];

        /// <summary>Scratch space for Unity to list what a box is overlapping.</summary>
        private readonly Collider[] overlapHits = new Collider[32];

        /// <summary>Remembers, per collider, what kind of thing it is (see Classify), so each is only worked out once.</summary>
        private readonly Dictionary<int, int> sceneryCache = new Dictionary<int, int>();

        /// <summary>The surfaces hit during one slide, so a slide along one never pushes into another.</summary>
        private readonly Vector3[] slidePlanes = new Vector3[6];

        private readonly List<Vector3> walls = new List<Vector3>();
        private readonly List<Vector3> stepWalls = new List<Vector3>();
        private readonly StringBuilder reply = new StringBuilder();
        private int sweepsAnswered;
        private int sweepNotes;
        private int liftsDone;
        private float nextCollisionReport;

        /// <summary>Whether this mod's changes to Subnautica's own code have been made yet.</summary>
        private bool gamePatched;

        // ---- The players' Minecraft characters ------------------------------------------------
        //
        // Minecraft catches each nearby player as it would draw them (pose, skin, armour, held
        // items) as a list of flat four-cornered faces, and sends that twenty times a second:
        //   "SKIN n w h pixels"                a picture (a skin, armour). Picture 0 is the block atlas.
        //   "AVSHAPE key faces"                the lasting part of a look: each face's picture, tint
        //                                      and place on the picture.
        //   "AV id key x y z flags corners"    where player "id" is and where the corners of
        //                                      those faces are just now.
        //   "AVGONE id"                        that player is no longer there to draw.
        // This side draws the faces and glides from each pose to the next. Subnautica's own
        // diver that Nitrox shows for another player is hidden while their Minecraft
        // character is being drawn in its place.

        /// <summary>Corner positions arrive as whole numbers of this many to a metre.</summary>
        private const float AvatarUnits = 512f;

        /// <summary>How many bytes describe one face in "AVSHAPE".</summary>
        private const int AvatarFaceBytes = 38;

        private class SkinPicture
        {
            public Texture2D picture;
            public byte[] pixels;
            public int width;
            public int height;
        }

        /// <summary>
        /// The lasting part of a look, worked out once. Each face is cut along the pixels of
        /// its picture, leaving out the see-through ones (the gaps in a helmet, the outline of
        /// a sword). What is kept is a recipe: every point of the finished shape is a certain
        /// way across and along one of the faces Minecraft sent. So when the corners of those
        /// faces move, the finished shape is just worked out again from the recipe.
        /// </summary>
        private class AvatarShape
        {
            public byte[] raw;
            public int faces;
            public int[] recipeFace;
            public float[] recipeAcross;
            public float[] recipeAlong;
            public Vector2[] spots;
            public int[][] triangles;
            public int[] pictures;
            public int[] tints;
            public bool usesAtlas;
            public bool cutWithAtlas;

            /// <summary>Goes up each time the recipe is worked out again, so whoever uses it can tell.</summary>
            public int version;
        }

        private class Avatar
        {
            public GameObject thing;
            public Mesh mesh;
            public MeshRenderer renderer;
            public AvatarShape shape;
            public int dressedVersion = -1;
            public bool dressedHurt;
            public bool dressedWithTemplate;

            /// <summary>The corners of Minecraft's faces: where they are heading, and where they are drawn just now.</summary>
            public Vector3[] target;
            public Vector3[] shown;
            public Vector3[] points;

            /// <summary>Where the player's feet are heading, and where they are drawn just now.</summary>
            public Vector3 place;
            public Vector3 shownPlace;
            public float heardAt;
            public bool hurt;
            public bool self;

            /// <summary>
            /// Moves at an even pace from one pose to the next, arriving just as the next one is
            /// due (a block a piston is pushing, a chest's lid): where it was when the newest
            /// pose arrived, and when that was. Creatures' limbs ease in and out instead.
            /// </summary>
            public bool even;
            public Vector3[] from;
            public float movedAt;

            /// <summary>Sitting on something in Minecraft (in a vehicle here), and whether it has been put on its seat this frame.</summary>
            public bool riding;
            public bool seated;

            /// <summary>A Minecraft boat this player is sitting in, and where it is from the player's feet. It is moved in step with the camera.</summary>
            public bool ownRide;
            public Vector3 rideOffset;

            /// <summary>
            /// Until when the shape is still changing (a pose that moved, or a new look), and
            /// whether it has been worked out at all. Something that has come to rest (a chest,
            /// a bed) isn't worked out again every frame.
            /// </summary>
            public float busyUntil;
            public bool workedOut;

            /// <summary>One of Minecraft's mobs, not a player.</summary>
            public bool mob;

            /// <summary>Drawn whitened: a creeper about to explode.</summary>
            public bool white;
            public bool dressedWhite;
        }

        private readonly Dictionary<int, SkinPicture> skins = new Dictionary<int, SkinPicture>();
        private readonly Dictionary<string, AvatarShape> avatarShapes = new Dictionary<string, AvatarShape>();
        private readonly Dictionary<int, Avatar> avatars = new Dictionary<int, Avatar>();
        private readonly Dictionary<long, Material> avatarMaterials = new Dictionary<long, Material>();
        private readonly List<int> avatarScratch = new List<int>();
        private bool avatarMaterialsFromTemplate;
        private GameObject avatarRoot;

        // Nitrox's divers (the other players as Subnautica draws them), hidden while a
        // Minecraft character stands in the same place.
        private Type diverType;
        private float nextDiverTypeSearch;
        private float nextDiverScan;
        private UnityEngine.Object[] divers = new UnityEngine.Object[0];
        private readonly Dictionary<GameObject, HashSet<Renderer>> hiddenDivers = new Dictionary<GameObject, HashSet<Renderer>>();
        private readonly List<GameObject> diverScratch = new List<GameObject>();

        // ---- Looking at yourself: F5 -------------------------------------------------------------
        //
        // F5 steps through Minecraft's three views: through the eyes, from behind, from the
        // front. The camera is only moved while the picture is being drawn and put back as soon
        // as it is finished, so everything else (where you aim, where Minecraft thinks your eyes are) carries
        // on as if it had never moved.

        /// <summary>Windows' number for the F5 key.</summary>
        private const int ViewKey = 0x74;

        /// <summary>How far back the camera sits in the outside views, in metres. Minecraft's own distance.</summary>
        private const float ViewDistance = 4f;

        /// <summary>0 through the eyes, 1 from behind, 2 from the front.</summary>
        private int viewMode;
        private bool viewKeyWasDown;

        /// <summary>The camera to move when it draws, or null while the view is through the eyes.</summary>
        private Camera outsideCamera;
        private Camera movedCamera;
        private Vector3 movedFrom;
        private Quaternion movedFromFacing;

        private void Awake()
        {
            running = true;
            self = this;

            // BepInEx keeps every plugin on one object, and Subnautica clears that object away
            // soon after starting unless it is marked as hidden. BepInEx has a setting for
            // this (HideManagerGameObject) that is off by default; marking it here means
            // nobody has to find that setting.
            gameObject.hideFlags = HideFlags.HideAndDontSave;
            DontDestroyOnLoad(gameObject);

            // Unity calls the first just before each camera draws; it moves the camera for the
            // outside views (F5). The second puts it back once the whole frame has been drawn.
            Camera.onPreCull += BeforeCameraDraws;
            StartCoroutine(RestoreCameraAfterEachFrame());

            Thread thread = new Thread(ConnectLoop) { IsBackground = true, Name = "Minecraft Link" };
            thread.Start();

            // The changes this mod makes to Subnautica's own code (see BlockSubnauticaToolKeys)
            // are NOT made here, as the game starts. Touching the game's input code this early
            // makes it set itself up before Nitrox (the multiplayer mod) has added its own key
            // bindings to it, and Nitrox then fails and the game freezes on its first screen.
            // They are made the first time there is a player in the world instead (see Update).
            Logger.LogInfo("Minecraft Link loaded; looking for Minecraft on port " + Port);
        }

        private void OnDestroy()
        {
            running = false;
            Camera.onPreCull -= BeforeCameraDraws;
            RestoreCamera();
            CloseOverlay();
            ShowBars();
            ShowBody();
            RemoveAllBlocks();
            ReleaseLifepod();
        }

        // ---- Main thread: runs every frame ------------------------------------------------

        private void Update()
        {
            Player player = Player.main;
            LiveMixin life = player != null ? player.liveMixin : null;
            ignoreBlocksNow = false;

            // In case a camera moved for an outside view never finished drawing.
            RestoreCamera();

            // Swapping between the two games' menus (see DrawMenuSwap).
            TendMenuSwap();

            // While Subnautica is stopped, Minecraft's player is held still too.
            TendHold();

            // 1. Deal with whatever Minecraft has sent.
            HandleIncoming(player);

            // Until the atlas is loaded, look for it every two seconds. After that, check ten
            // times a second for a newer version: Minecraft writes one whenever a moving
            // picture (fire) has moved on.
            if (atlasPath != null && Time.unscaledTime >= nextAtlasTry)
            {
                nextAtlasTry = Time.unscaledTime + (blockAtlas == null ? 2f : 0.1f);
                LoadAtlas();
            }

            // Quitting to Subnautica's menu and loading a save clears away everything in the
            // world, Minecraft's blocks included, while the link itself stays up. Minecraft
            // is asked to describe everything afresh ("RESYNC").
            if (linked && blocks.Count > 0 && blockRoot == null)
            {
                RemoveAllBlocks();
                Send("RESYNC");
                Logger.LogInfo("Minecraft's blocks were cleared away with the world; asked Minecraft for them again");
            }

            MoveShownItems();
            MoveAvatars();
            SettleHabitatLegs();
            TendGrabs();
            MoveDebris();
            TendLights(player);

            // Minecraft's HUD picture. Done before the "is there a player?" check so the HUD goes
            // away properly at the main menu too.
            UpdateOverlay();

            // No player yet (main menu, loading): nothing to watch.
            if (life == null)
            {
                ridingNow = false;
                wasRiding = false;
                riddenVehicle = null;
                riddenBody = null;
                riddenLife = null;
                lastHealth = -1f;
                lastFood = -1f;
                lastOxygen = -1f;
                return;
            }

            // In a world, with a player: now it is safe to make this mod's changes to
            // Subnautica's own code. Done once.
            if (!gamePatched)
            {
                gamePatched = true;
                BlockSubnauticaToolKeys();
            }

            // 2. Compare health with what the games last agreed on. A difference, up or down,
            //    means something here changed it (a bite, a first aid kit, respawning).
            float health = life.health;

            if (lastHealth >= 0f && Mathf.Abs(health - lastHealth) > SmallestChange)
            {
                if (health <= 0f)
                {
                    Send("DEATH");
                }
                else
                {
                    Send("HEALTH " + (health / life.maxHealth).ToString("0.####", CultureInfo.InvariantCulture));
                }
            }

            lastHealth = health;

            // 3. Food and water.
            Survival survival = player.GetComponent<Survival>();

            if (survival != null)
            {
                // Hunger is Minecraft's business alone. Left to itself, Subnautica would also hurt
                // a starving player and heal a well-fed one, on top of Minecraft doing both; this
                // is the game's own switch for leaving food, water and what they do alone.
                // Only while linked: with Minecraft closed, Subnautica's hunger and thirst are its own again.
                if (linked && !survival.freezeStats)
                {
                    survival.freezeStats = true;
                    frozeHunger = true;
                }

                // Minecraft has no thirst, so thirst is switched off: the water bar never drops.
                // (One number set once per frame; the cost is too small to measure.)
                if (linked && survival.water < FullStat)
                {
                    survival.water = FullStat;
                }

                // The link has ended: hunger and thirst are switched back on, unless the game
                // itself has them held (it does while the player sleeps or sits).
                if (!linked && frozeHunger)
                {
                    frozeHunger = false;

                    try
                    {
                        survival.freezeStats = player.IsFrozenStats();
                    }
                    catch (Exception)
                    {
                        survival.freezeStats = false;
                    }
                }

                // Minecraft is in charge of hunger. Once it has told us its level:
                if (lastFood >= 0f)
                {
                    float gained = survival.food - lastFood;

                    if (gained > SmallestMeal)
                    {
                        // Food went UP, so the player ate here. Tell Minecraft how much, and
                        // keep the food for now; Minecraft replies with the new level.
                        Send("ATE " + Mathf.Clamp01(gained / FullStat).ToString("0.####", CultureInfo.InvariantCulture));
                        lastFood = Mathf.Min(survival.food, FullStat);
                    }

                    // Otherwise undo Subnautica's own hunger drain: the bar only moves when
                    // Minecraft's does.
                    survival.food = lastFood;
                }
            }

            // 4. Movement. Minecraft moves the player; this side only reports.
            //    Numbers are sent as Unity's raw values; Minecraft converts them.
            if (health <= 0f)
            {
                wasDead = true;
            }
            else if (wasDead)
            {
                // Just respawned: Minecraft must restart from the new spot.
                wasDead = false;
                spawnPending = true;
            }

            // In a vehicle, Subnautica is in charge of where the player is (see "Vehicles").
            // At the helm of the Cyclops counts too: the sub is driven from there, and Subnautica
            // holds the player at the wheel.
            Vehicle vehicle = linked && health > 0f ? player.GetVehicle() : null;
            SubRoot helm = null;

            if (linked && health > 0f && vehicle == null)
            {
                try
                {
                    SubRoot around = player.currentSub;

                    if (around != null && around.isCyclops && player.isPiloting)
                    {
                        helm = around;
                    }
                }
                catch (Exception)
                {
                    helm = null;
                }
            }

            bool riding = vehicle != null || helm != null;
            ridingNow = riding;

            if (riding != wasRiding)
            {
                wasRiding = riding;
                Logger.LogInfo(riding ? "In a vehicle (" + (vehicle != null ? vehicle.GetType().Name : "Cyclops helm") + "): Subnautica moves the player" : "Out of the vehicle: Minecraft moves the player again");

                // The PRAWN suit words its prompt once and keeps it: have it word it afresh, so it
                // names Alt while linked (and E again afterwards).
                if (riding)
                {
                    riddenVehicle = vehicle;
                    riddenBody = vehicle != null ? vehicle.transform : helm.transform;
                    riddenLife = vehicle != null ? vehicle.liveMixin : helm.GetComponent<LiveMixin>();
                }

                if (riddenVehicle != null && riddenVehicle.GetType().Name == "Exosuit")
                {
                    SetField(riddenVehicle, "hasInitStrings", false);
                }

                if (!riding)
                {
                    riddenVehicle = null;
                    riddenBody = null;
                    riddenLife = null;
                    // Minecraft starts again from wherever Subnautica has put the player.
                    spawnPending = true;
                    hasPlaced = false;
                    targetTime = -100f;
                }
            }

            // Alt is watched every frame while riding, so a tap is never judged from a stale reading.
            if (riding)
            {
                AltTapped();
            }

            if (riding && Time.unscaledTime >= nextRideReport)
            {
                nextRideReport = Time.unscaledTime + 0.05f;
                Vector3 seat = EyePosition(player);

                // And how the vehicle is doing: its hull and its power, each from 0 to 1. Minecraft
                // shows them as a mount's hearts and in place of the experience bar.
                float hull = 1f;
                float power = 0f;

                try
                {
                    if (riddenLife != null && riddenLife.maxHealth > 0f)
                    {
                        hull = Mathf.Clamp01(riddenLife.health / riddenLife.maxHealth);
                    }

                    float charge = 0f;
                    float capacity = 0f;

                    if (vehicle != null)
                    {
                        EnergyInterface cells = vehicle.GetComponentInChildren<EnergyInterface>();

                        if (cells != null)
                        {
                            cells.GetValues(out charge, out capacity);
                        }
                    }
                    else if (helm.powerRelay != null)
                    {
                        // The Cyclops: all its power cells together.
                        charge = helm.powerRelay.GetPower();
                        capacity = helm.powerRelay.GetMaxPower();
                    }

                    power = capacity > 0f ? Mathf.Clamp01(charge / capacity) : 0f;
                }
                catch (Exception)
                {
                    // A vehicle without one or the other: the bars just show full hull, no power.
                }

                // The PRAWN suit's jump jets have a reserve of their own, from 0 to 1 (-1: this
                // vehicle has none). Minecraft shows it in place of the power while it is changing.
                float thrust = -1f;

                try
                {
                    if (vehicle != null && vehicle.GetType().Name == "Exosuit")
                    {
                        if (thrustField == null)
                        {
                            thrustField = vehicle.GetType().GetField("thrustPower", BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic);
                        }

                        if (thrustField != null)
                        {
                            thrust = Mathf.Clamp01((float)thrustField.GetValue(vehicle));
                        }
                    }
                }
                catch (Exception)
                {
                    thrust = -1f;
                }

                Send(string.Format(CultureInfo.InvariantCulture, "RIDE {0:0.###} {1:0.###} {2:0.###} {3:0.#} {4:0.###} {5:0.###} {6:0.###}",
                    seat.x, seat.y - MinecraftEyeHeight, seat.z, (vehicle != null ? vehicle.transform : helm.transform).eulerAngles.y, hull, power, thrust));
            }

            if (spawnPending && health > 0f && riding)
            {
                // In a vehicle when the link starts (or Minecraft asks where the player is):
                // Minecraft still has to be told where to begin, or it never brings its player
                // over and the "RIDE" lines above go unheeded until the player gets out.
                Vector3 seated = EyePosition(player);
                Send(string.Format(CultureInfo.InvariantCulture, "SPAWN {0:0.###} {1:0.###} {2:0.###}", seated.x, seated.y - MinecraftEyeHeight, seated.z));
                spawnPending = false;
            }

            if (spawnPending && health > 0f && !riding)
            {
                // Minecraft counts a position from the feet, with the eyes 1.62 above. Send the spot
                // that puts Minecraft's eyes where Subnautica's camera is.
                Vector3 eyes = EyePosition(player);
                float feet = eyes.y - MinecraftEyeHeight;

                // Subnautica's character isn't exactly Minecraft's height, so that spot can leave
                // Minecraft's feet inside the floor, and something that starts inside the floor
                // falls through it. Raise the spot until a Minecraft-sized player fits.
                RefreshSolidLayers(player);
                Vector3 standingHalf = new Vector3(0.3f, 0.9f, 0.3f);
                feet += FindLift(new Vector3(eyes.x, feet + standingHalf.y, eyes.z), standingHalf, player, 1.5f);

                Send(string.Format(CultureInfo.InvariantCulture, "SPAWN {0:0.###} {1:0.###} {2:0.###}", eyes.x, feet, eyes.z));
                spawnPending = false;
                targetTime = -100f;
                hasPlaced = false;

                // Ignore positions still arriving from the old spot.
                waitingForMinecraft = true;
                waitSpot = eyes;
                waitUntil = Time.unscaledTime + 3f;
            }

            // Which controls are held and where the camera points.
            // (unscaledTime keeps counting while Subnautica is paused.)
            if (Time.unscaledTime >= nextInputTime)
            {
                nextInputTime = Time.unscaledTime + InputInterval;

                // x = strafe (right is +), y = up/down (jump is +), z = forward/back (forward is +).
                // Nothing while Subnautica's command console is being typed into.
                // Nor in a vehicle: there the movement keys are the vehicle's.
                // Nor with Subnautica's own menu up.
                Vector3 move = consoleOpen || riding || SubnauticaMenuOpen() ? Vector3.zero : GameInput.GetMoveDirection();

                // Minecraft's keys: Control sprints, Shift sneaks (and sinks, in water). Only while
                // actually playing: not with a menu or the PDA open, or another window in front.
                bool playing = Application.isFocused && Cursor.lockState == CursorLockMode.Locked && !typing && !screenOpen && !consoleOpen;
                bool sprint = playing && !riding && KeyHeld(ControlKey);
                bool sneak = playing && !riding && KeyHeld(ShiftKey);
                Camera view = Camera.main;
                Vector3 angles = view != null ? view.transform.eulerAngles : player.transform.eulerAngles;

                // Mouse buttons: left attacks (swings), right uses the held item (eats, say).
                bool leftDown = KeyHeld(LeftMouseButton);
                bool rightDown = KeyHeld(RightMouseButton);

                // A button that was already down when a menu closed (the click that closed the
                // Fabricator's menu, say) isn't an attack: it only counts once pressed afresh.
                if (playing && !wasPlaying)
                {
                    leftFromMenu = leftDown;
                    rightFromMenu = rightDown;
                }

                wasPlaying = playing;
                leftFromMenu &= leftDown;
                rightFromMenu &= rightDown;

                // In a vehicle both buttons are the vehicle's, except that the right one still eats
                // whatever food Minecraft's hand is holding.
                bool attack = playing && !riding && leftDown && !leftFromMenu;
                bool use = playing && rightDown && !rightFromMenu && (!riding || foodInHand);

                SendAim(player, view);

                Send(string.Format(CultureInfo.InvariantCulture, "INPUT {0:0.##} {1:0.##} {2} {3} {4} {5:0.##} {6:0.##} {7} {8}",
                    move.z, move.x, move.y > 0.1f ? 1 : 0, sneak ? 1 : 0, sprint ? 1 : 0, angles.y, angles.x, attack ? 1 : 0, use ? 1 : 0));
            }

            // Minecraft's hotbar: number keys pick a slot, the scroll wheel moves along it.
            // Checked every frame so a quick tap is never missed.
            SendHotbarKeys();

            // F5 changes the view, as in Minecraft.
            WatchViewKey();

            // F9 opens Subnautica's command console; the held tool's light and lift are reported; the tool prompt is kept clear of the hotbar.
            WatchConsole();
            ReportToolState();
            RaiseToolPrompt();

            // Is a Fabricator open? If so its panel of Minecraft materials is drawn (see OnGUI).
            WatchFabricators(player);
            WatchTradeKeys();

            // Creatures set alight by Fire Aspect keep burning; kills are passed on to Minecraft.
            TendBurning(player);

            string killed;

            while (killNotes.TryDequeue(out killed))
            {
                Send(killed);
            }

            // 5. Oxygen: Subnautica is in charge; Minecraft's bubble bar just shows it.
            OxygenManager oxygen = player.oxygenMgr;

            if (oxygen != null)
            {
                float capacity = oxygen.GetOxygenCapacity();
                float fraction = capacity > 0f ? Mathf.Clamp01(oxygen.GetOxygenAvailable() / capacity) : 1f;
                bool justRefilled = fraction >= 0.999f && lastOxygen < 0.999f;

                if (lastOxygen < 0f || justRefilled || Mathf.Abs(fraction - lastOxygen) > SmallestOxygenChange)
                {
                    Send("OXYGEN " + fraction.ToString("0.####", CultureInfo.InvariantCulture));
                    lastOxygen = fraction;
                }
            }

            // 6. Water: is the player somewhere dry, and how fast do they swim? Checked a few
            //    times a second, and sent only when it changes.
            if (Time.unscaledTime >= nextWaterCheck)
            {
                nextWaterCheck = Time.unscaledTime + 0.1f;

                // (A vehicle's cabin counts as dry: otherwise Minecraft, seeing its player below sea
                // level, would start drowning them. If the vehicle runs out of power Subnautica's
                // own oxygen drops, and Minecraft shows that as usual.)
                int dry = (ridingNow || ReadBool(player, "IsInsideWalkable") || ReadBool(player, "precursorOutOfWater")) ? 1 : 0;

                if (dry != lastDry)
                {
                    Send("DRY " + dry);
                    lastDry = dry;
                }

                // Which of Subnautica's regions this is: digging gives different Minecraft materials in each.
                string biome = null;

                try
                {
                    biome = player.GetBiomeString();
                }
                catch (Exception)
                {
                    biome = null;
                }

                if (!string.IsNullOrEmpty(biome) && biome != lastBiome)
                {
                    Send("BIOME " + biome.Replace(' ', '_'));
                    lastBiome = biome;
                }

                // Whether it is night here: a Minecraft bed can be slept in while it is.
                int night = 0;

                try
                {
                    night = DayNightCycle.main != null && !DayNightCycle.main.IsDay() ? 1 : 0;
                }
                catch (Exception)
                {
                    night = 0;
                }

                if (night != lastNight)
                {
                    Send("NIGHT " + night);
                    lastNight = night;
                }

                // Aboard the Cyclops (walking about, or at its helm), Minecraft's mobs outside
                // can't get at the player: the hull is in the way.
                int aboard = 0;

                try
                {
                    SubRoot hull = player.currentSub;
                    aboard = hull != null && hull.isCyclops ? 1 : 0;
                }
                catch (Exception)
                {
                    aboard = 0;
                }

                if (aboard != lastAboard)
                {
                    Send("ABOARD " + aboard);
                    lastAboard = aboard;
                }

                float swimSpeed = ReadSwimSpeed(player);

                if (Mathf.Abs(swimSpeed - lastSwimSpeed) > 0.05f)
                {
                    Send("SWIMSPEED " + swimSpeed.ToString("0.###", CultureInfo.InvariantCulture));
                    lastSwimSpeed = swimSpeed;
                }
            }

            // A line in the log every 10 seconds, to help with troubleshooting.
            if (Time.unscaledTime >= nextCollisionReport)
            {
                nextCollisionReport = Time.unscaledTime + 10f;
                Vector3 body = player.transform.position;
                Logger.LogInfo(string.Format(CultureInfo.InvariantCulture,
                    "Collision: answered {0} movement checks in 10 s near ({1:0.#}, {2:0.#}, {3:0.#}); camera is {4:0.##} above the player's position; dry = {5}; swim speed = {6:0.##}; lifted clear {7} times",
                    sweepsAnswered, body.x, body.y, body.z, EyePosition(player).y - body.y, lastDry, lastSwimSpeed, liftsDone));
                liftsDone = 0;
                sweepsAnswered = 0;
            }
        }

        /// <summary>Handles every line Minecraft has sent since last time.</summary>
        private void HandleIncoming(Player player)
        {
            LiveMixin life = player != null ? player.liveMixin : null;
            string line;

            while (incoming.TryDequeue(out line))
            {
                // One line going wrong must not stop the rest of the frame's work.
                try
                {
                    HandleLine(line, player, life);
                }
                catch (Exception e)
                {
                    string kind = line.Split(' ')[0];

                    if (warnedMissing.Add("line " + kind))
                    {
                        Logger.LogWarning("Could not act on a \"" + kind + "\" line from Minecraft: " + e);
                    }
                }
            }
        }

        /// <summary>
        /// Runs every frame after everything else has moved. Carries the Subnautica character to
        /// where Minecraft's movement has put the player, overriding Subnautica's own movement.
        /// </summary>
        private void LateUpdate()
        {
            Player player = Player.main;

            // Minecraft waits for collision answers, so look for questions here as well as in
            // Update(): twice a frame halves the wait.
            HandleIncoming(player);

            RestoreCamera();

            // Which camera to move when it draws (see BeforeCameraDraws): the main one, but only in
            // an outside view, while Minecraft is in charge, and not while Subnautica's PDA is up
            // or the game is playing one of its own animations with the player.
            outsideCamera = viewMode != 0 && player != null && OverlayShowing() && !pdaOpen && !player.cinematicModeActive
                && Time.unscaledTime - targetTime <= TargetTimeout ? Camera.main : null;

            // Other players' Subnautica divers step aside for their Minecraft characters.
            TendDivers();

            // The light from whatever is in Minecraft's hand stays with the eyes.
            TendHeldLight();

            // While Minecraft's hand is on screen, Subnautica's own arms and tools are not.
            // With a Minecraft screen open, the mouse pointer is shown and free to move, and
            // its position and clicks go to Minecraft. Done last in the frame, after Subnautica
            // has had its say about the pointer.
            if (screenOpen)
            {
                FreePointer(true);
                SendPointer();
            }

            // The PDA is held in Subnautica's hands, so they come back while it is open.
            if (player != null && OverlayShowing() && !pdaOpen)
            {
                // Hands are kept empty, unless Minecraft is holding the token for a tool.
                if (equippedTool == null)
                {
                    PutAwayTool();
                }

                HideBody(player);
            }
            else
            {
                ShowBody();
            }

            // Aboard a moving Cyclops, the player is carried along with it.
            TendCarry(player);
            TendHullCollisions(player);
            TendBlocksNearSubs();
            TendWaterLight();
            TendPlayerLights();

            // Minecraft characters sitting in vehicles are put on their seats.
            SeatRiders();

            // A vehicle's module bar is shown, off to the left.
            TendModuleBar();

            // In a vehicle the character stays where Subnautica has it.
            if (ridingNow)
            {
                hasPlaced = false;
                wasCinematic = false;
                return;
            }

            if (player == null || Time.unscaledTime - targetTime > TargetTimeout)
            {
                return;
            }

            // Subnautica is playing its own animation with the player (climbing through a hatch,
            // say). Leave it alone, and have Minecraft start again from wherever it ends.
            if (player.cinematicModeActive)
            {
                wasCinematic = true;
                hasPlaced = false;
                return;
            }

            if (wasCinematic)
            {
                wasCinematic = false;
                spawnPending = true;
                targetTime = -100f;
                return;
            }

            Vector3 current = player.transform.position;

            // Subnautica moved the character a long way by itself since last frame (a hatch, a
            // ladder, a teleport). Go along with it: Minecraft restarts from the new spot.
            if (hasPlaced && (current - lastPlaced).sqrMagnitude > WarpDistance * WarpDistance)
            {
                hasPlaced = false;
                spawnPending = true;
                targetTime = -100f;
                return;
            }

            // Minecraft sent where the EYES are. The glide is done on the eye position, and the
            // character is then put wherever makes Subnautica's camera sit exactly there.
            //
            // Two things would otherwise leak into the picture. Between frames Subnautica moves
            // the character a little by itself, so the glide starts from where THIS MOD last put
            // the eyes, not from where they are now. And Subnautica dips its camera when it
            // thinks the player has landed; measuring the camera's offset afresh every frame
            // cancels that dip out.
            Vector3 eyeOffset = EyePosition(player) - current;
            Vector3 fromEyes = hasPlaced ? lastEyes : current + eyeOffset;
            Vector3 nextEyes;

            if (!hasPlaced || (target - fromEyes).sqrMagnitude > SnapDistance * SnapDistance)
            {
                // The first placing, or a big jump: go straight there.
                nextEyes = target;
                previousTarget = target;
                followOffset = Vector3.zero;
            }
            else
            {
                // Minecraft reports 20 times a second. Between two reports the eyes travel in a
                // straight line from the one to the other at a steady speed, which is what makes
                // fast movement (an elytra, sprint-swimming) smooth rather than a series of
                // lunges. If the next report is a little late, the line is carried on a short way
                // rather than stopping dead.
                float along = Mathf.Min((Time.unscaledTime - targetTime) / TickSeconds, 1.35f);
                followOffset *= Mathf.Exp(-12f * Time.unscaledDeltaTime);
                nextEyes = Vector3.LerpUnclamped(previousTarget, target, along) + followOffset;
            }

            Vector3 next = nextEyes - eyeOffset;
            lastEyes = nextEyes;

            // Your own Minecraft character (seen in the outside views) moves with the eyes, in
            // the same frame, so it never trails behind the camera.
            foreach (Avatar avatar in avatars.Values)
            {
                // (And so does the boat you are sitting in: drawn on its own it glides at a
                // slightly different pace from the camera, which shows as a jitter.)
                if ((avatar.self || avatar.ownRide) && avatar.thing != null)
                {
                    avatar.shownPlace = lastEyes - Vector3.up * targetEyeHeight + (avatar.ownRide ? avatar.rideOffset : Vector3.zero);
                    avatar.thing.transform.position = avatar.shownPlace;
                }
            }

            player.SetPosition(next);
            lastPlaced = next;
            hasPlaced = true;

            // Take the character out of Unity's physics as well: "kinematic" means gravity, forces
            // and collisions no longer move it, so the only thing that does is this mod. Its
            // shape still counts for Subnautica's trigger areas (hatches, air pockets).
            Rigidbody body = player.rigidBody;

            if (body != null)
            {
                if (!body.isKinematic)
                {
                    body.velocity = Vector3.zero;
                    body.angularVelocity = Vector3.zero;
                    body.isKinematic = true;
                    heldPlayerBody = body;
                }
            }
        }

        private void HandleLine(string line, Player player, LiveMixin life)
        {
            if (line.StartsWith("MCPOS ", StringComparison.Ordinal))
            {
                // "MCPOS x y z eye": where Minecraft's player is (feet), in Minecraft's coordinates,
                // and how high the eyes are above that. The two games' z axes point opposite
                // ways, so z is flipped.
                string[] parts = line.Substring(6).Split(' ');
                float x, y, z;

                float eye = MinecraftEyeHeight;

                if (parts.Length >= 4)
                {
                    float.TryParse(parts[3], NumberStyles.Float, CultureInfo.InvariantCulture, out eye);
                }

                // Sitting on a seat in Minecraft: that position is the vehicle's, not somewhere
                // the player has moved to. While riding it only shows Minecraft is still there;
                // just after getting out, it is left over from the ride and is ignored, so the
                // player isn't pulled back into the seat while Minecraft catches up.
                if (parts.Length >= 5 && parts[4] == "1")
                {
                    if (ridingNow)
                    {
                        targetTime = Time.unscaledTime;
                    }

                    return;
                }

                if (parts.Length >= 3
                    && float.TryParse(parts[0], NumberStyles.Float, CultureInfo.InvariantCulture, out x)
                    && float.TryParse(parts[1], NumberStyles.Float, CultureInfo.InvariantCulture, out y)
                    && float.TryParse(parts[2], NumberStyles.Float, CultureInfo.InvariantCulture, out z))
                {
                    // (Plus however far the Cyclops has carried the player since Minecraft last took that into account.)
                    Vector3 eyes = new Vector3(x, y + eye, -z) + CarryOwed();

                    if (waitingForMinecraft)
                    {
                        // Still a position from before Minecraft was told to start somewhere new.
                        if ((eyes - waitSpot).sqrMagnitude > WarpDistance * WarpDistance && Time.unscaledTime < waitUntil)
                        {
                            return;
                        }

                        waitingForMinecraft = false;
                    }

                    // The glide now runs from the last reported position to this one. Whatever
                    // gap there is between where the eyes are drawn this instant and the start
                    // of that glide is kept, and closed gradually, so nothing jumps.
                    if (hasPlaced && Time.unscaledTime - targetTime < 0.25f && (eyes - target).sqrMagnitude < SnapDistance * SnapDistance)
                    {
                        previousTarget = target;
                        followOffset = lastEyes - previousTarget;

                        if (followOffset.sqrMagnitude > 9f)
                        {
                            followOffset = Vector3.zero;
                        }
                    }
                    else
                    {
                        previousTarget = eyes;
                        followOffset = Vector3.zero;
                    }

                    target = eyes;
                    targetTime = Time.unscaledTime;
                    targetEyeHeight = eye;
                }

                return;
            }

            if (line.StartsWith("AV ", StringComparison.Ordinal))
            {
                HandleAvatar(line.Substring(3));
                return;
            }

            if (line.StartsWith("AVSHAPE ", StringComparison.Ordinal))
            {
                HandleAvatarShape(line.Substring(8));
                return;
            }

            if (line.StartsWith("SKIN ", StringComparison.Ordinal))
            {
                HandleSkin(line.Substring(5));
                return;
            }

            if (line.StartsWith("AVGONE ", StringComparison.Ordinal))
            {
                HandleAvatarGone(line.Substring(7));
                return;
            }

            if (line == "AVCLEAR")
            {
                // Minecraft has forgotten which looks it sent and will send them again as needed.
                avatarShapes.Clear();
                return;
            }

            if (line == "WHERE")
            {
                // Minecraft has lost track of where to put its player (see LinkSession in the
                // Minecraft mod): tell it again as soon as there is a player to tell it about.
                spawnPending = true;
                return;
            }

            if (line.StartsWith("VHURT ", StringComparison.Ordinal))
            {
                // Something in Minecraft (a Drowned, a creeper) hit the player while in a vehicle:
                // the vehicle's hull takes it instead.
                float harm;

                if (riddenLife != null && riddenBody != null && riddenVehicle != null
                    && float.TryParse(line.Substring(6), NumberStyles.Float, CultureInfo.InvariantCulture, out harm) && harm > 0f)
                {
                    riddenLife.TakeDamage(harm, riddenBody.position, DamageType.Normal, null);
                }

                return;
            }

            if (line.StartsWith("GIVE ", StringComparison.Ordinal))
            {
                // Minecraft has taken a material from its inventory in exchange for one of Subnautica's.
                string[] given = line.Substring(5).Split(' ');
                int howMany;

                try
                {
                    if (given.Length == 2 && int.TryParse(given[1], NumberStyles.Integer, CultureInfo.InvariantCulture, out howMany) && howMany > 0 && howMany <= 64)
                    {
                        TechType made = (TechType)Enum.Parse(typeof(TechType), given[0]);
                        CraftData.AddToInventory(made, howMany, false, true);
                        Logger.LogInfo("Minecraft handed over materials for " + howMany + " " + given[0]);
                    }
                }
                catch (Exception e)
                {
                    Logger.LogWarning("Could not add " + line.Substring(5) + " to the inventory: " + e.Message);
                }

                return;
            }

            if (line.StartsWith("SPACE ", StringComparison.Ordinal))
            {
                // "id x y z half": would a box this big, centred here, be under water and clear
                // of everything solid? Minecraft asks before putting a ghast there.
                string[] asked = line.Substring(6).Split(' ');
                float sx, sy, sz, half;

                if (asked.Length == 5
                    && float.TryParse(asked[1], NumberStyles.Float, CultureInfo.InvariantCulture, out sx)
                    && float.TryParse(asked[2], NumberStyles.Float, CultureInfo.InvariantCulture, out sy)
                    && float.TryParse(asked[3], NumberStyles.Float, CultureInfo.InvariantCulture, out sz)
                    && float.TryParse(asked[4], NumberStyles.Float, CultureInfo.InvariantCulture, out half))
                {
                    // z runs the other way in Subnautica.
                    Vector3 middle = new Vector3(sx, sy, -sz);
                    bool clear = player != null && !WorldLoading() && middle.y + half < -0.5f
                        && !Physics.CheckBox(middle, Vector3.one * half, Quaternion.identity, ~0, QueryTriggerInteraction.Ignore);
                    Send("SPACED " + asked[0] + (clear ? " 1" : " 0"));
                }

                return;
            }

            if (line == "SLEPT")
            {
                // A night's sleep in a Minecraft bed: skip ahead exactly as Subnautica's own beds do.
                try
                {
                    if (DayNightCycle.main != null && !DayNightCycle.main.IsInSkipTimeMode())
                    {
                        bool skipped = DayNightCycle.main.SkipTime(SleepSkipsSeconds, SleepTakesSeconds);
                        Logger.LogInfo("Slept in a Minecraft bed: " + (skipped ? "skipping the night" : "Subnautica would not skip the night"));
                    }
                }
                catch (Exception e)
                {
                    Logger.LogWarning("Could not skip the night: " + e.Message);
                }

                return;
            }

            if (line.StartsWith("ACK ", StringComparison.Ordinal))
            {
                // Minecraft has moved its player along with the Cyclops, up to this "CARRY".
                int upTo;

                if (int.TryParse(line.Substring(4), NumberStyles.Integer, CultureInfo.InvariantCulture, out upTo))
                {
                    while (carriesSent.Count > 0 && carriesSent.Peek().Key <= upTo)
                    {
                        carryAcked = carriesSent.Dequeue().Value;
                    }
                }

                return;
            }

            if (line.StartsWith("EDIBLE ", StringComparison.Ordinal))
            {
                // Whether Minecraft's hand holds something to eat or drink (see "Vehicles").
                foodInHand = line.EndsWith("1", StringComparison.Ordinal);
                return;
            }

            if (line.StartsWith("CHEATS ", StringComparison.Ordinal))
            {
                // Whether the Minecraft world allows this player its cheat commands. Subnautica's console follows suit.
                cheatsAllowed = line.EndsWith("1", StringComparison.Ordinal);
                return;
            }

            if (line.StartsWith("HELDLIGHT ", StringComparison.Ordinal))
            {
                HandleHeldLight(line.Substring(10));
                return;
            }

            if (line.StartsWith("PLIGHT ", StringComparison.Ordinal))
            {
                HandlePlayerLight(line.Substring(7));
                return;
            }

            if (line.StartsWith("PULL ", StringComparison.Ordinal))
            {
                HandlePull(line.Substring(5));
                return;
            }

            if (line.StartsWith("GRAB ", StringComparison.Ordinal))
            {
                HandleGrab(line.Substring(5), player);
                return;
            }

            if (line.StartsWith("LETGO ", StringComparison.Ordinal))
            {
                int released;

                if (int.TryParse(line.Substring(6).Trim(), NumberStyles.Integer, CultureInfo.InvariantCulture, out released))
                {
                    grabs.Remove(released);
                }

                return;
            }

            if (line.StartsWith("PROBE ", StringComparison.Ordinal))
            {
                HandleProbe(line.Substring(6));
                return;
            }

            if (line.StartsWith("OVERLAY ", StringComparison.Ordinal))
            {
                // "OVERLAY path": where Minecraft's shared HUD file is. (The path can contain spaces.)
                CloseOverlay();
                overlayPath = line.Substring(8);
                return;
            }

            if (line.StartsWith("EQUIP ", StringComparison.Ordinal))
            {
                // "EQUIP Knife": the token for this tool is in Minecraft's hand. "EQUIP -": none is.
                string tool = line.Substring(6).Trim();
                equippedTool = tool == "-" || tool.Length == 0 ? null : tool;
                nextToolCheck = 0f;
                return;
            }

            if (line.StartsWith("MCSCREEN ", StringComparison.Ordinal))
            {
                bool open = line.EndsWith("1", StringComparison.Ordinal);

                if (screenOpen && !open)
                {
                    // The screen closed: typing is over, and the pointer goes back to steering.
                    typing = false;
                    FreePointer(false);

                    // If it was Minecraft's menu, and closed to make way for Subnautica's, that opens now.
                    AfterMinecraftMenu();
                }

                // A button already held as the screen opens (the right button that opened a
                // chest) isn't a click on the screen: only presses made from now on count.
                if (open && !screenOpen)
                {
                    sentButtons[0] = KeyHeld(LeftMouseButton);
                    sentButtons[1] = KeyHeld(RightMouseButton);
                }

                screenOpen = open;
                sentPointerX = -1f;
                return;
            }

            if (line.StartsWith("BLOCK ", StringComparison.Ordinal))
            {
                HandleBlock(line.Substring(6));
                return;
            }

            if (line.StartsWith("MODEL ", StringComparison.Ordinal))
            {
                HandleModel(line.Substring(6));
                return;
            }

            if (line.StartsWith("MODELGONE ", StringComparison.Ordinal))
            {
                // "MODELGONE id": no block has this shape any more (a shape of flowing water
                // that has flowed on). Forget it, so the shapes don't pile up for ever.
                int goneId;
                BlockModel gone;

                if (int.TryParse(line.Substring(10).Trim(), NumberStyles.Integer, CultureInfo.InvariantCulture, out goneId) && blockModels.TryGetValue(goneId, out gone))
                {
                    if (gone.mesh != null)
                    {
                        Destroy(gone.mesh);
                    }

                    blockModels.Remove(goneId);
                    blockLights.Remove(goneId);
                }

                return;
            }

            if (line == "LAVACHECK")
            {
                AnswerLavaCheck(player);
                return;
            }

            if (line.StartsWith("ITEM ", StringComparison.Ordinal))
            {
                HandleItem(line.Substring(5));
                return;
            }

            if (line.StartsWith("LIGHT ", StringComparison.Ordinal))
            {
                HandleLight(line.Substring(6));
                return;
            }

            if (line.StartsWith("DEBRIS ", StringComparison.Ordinal))
            {
                HandleDebris(line.Substring(7));
                return;
            }

            if (line.StartsWith("FX ", StringComparison.Ordinal))
            {
                HandleEffect(line.Substring(3), player);
                return;
            }

            if (line.StartsWith("CRACKS ", StringComparison.Ordinal))
            {
                HandleCracks(line.Substring(7));
                return;
            }

            if (line.StartsWith("CHUNKGONE ", StringComparison.Ordinal))
            {
                HandleChunkGone(line.Substring(10));
                return;
            }

            if (line.StartsWith("CRACK ", StringComparison.Ordinal))
            {
                HandleCrack(line.Substring(6));
                return;
            }

            if (line.StartsWith("BLAST ", StringComparison.Ordinal))
            {
                HandleBlast(line.Substring(6), player);
                return;
            }

            if (line.StartsWith("SHOT ", StringComparison.Ordinal))
            {
                HandleShot(line.Substring(5));
                return;
            }

            if (line.StartsWith("RAYS ", StringComparison.Ordinal))
            {
                AnswerRays(line.Substring(5), player);
                return;
            }

            if (line.StartsWith("HURT ", StringComparison.Ordinal))
            {
                HandleHurt(line.Substring(5), player);
                return;
            }

            if (line.StartsWith("ITEMGONE ", StringComparison.Ordinal))
            {
                HandleItemGone(line.Substring(9));
                return;
            }

            if (line.StartsWith("ITEMMODEL ", StringComparison.Ordinal))
            {
                HandleItemModel(line.Substring(10));
                return;
            }

            if (line.StartsWith("ATLAS ", StringComparison.Ordinal))
            {
                // "ATLAS path": where Minecraft wrote its block atlas. (The path can contain spaces.)
                atlasPath = line.Substring(6);
                nextAtlasTry = 0f;
                atlasNumber = -1;
                return;
            }

            if (line.StartsWith("SWEEP ", StringComparison.Ordinal))
            {
                AnswerSweep(line.Substring(6), player);
                return;
            }

            if (line == Connected)
            {
                ErrorMessage.AddMessage("Minecraft linked");
                // Send the oxygen level and water details afresh; Minecraft sends its health and hunger.
                lastOxygen = -1f;
                lastDry = -1;
                sentHold = -1;
                lastAboard = -1;
                lastNight = -1;
                lastBiome = null;
                carryTotal = carrySent = carryAcked = Vector3.zero;
                carriesSent.Clear();
                lastSwimSpeed = -1f;
                // Tell Minecraft where to start from.
                spawnPending = true;
                linked = true;
                sentScreenWidth = 0;
                sentTools = null;
                equippedTool = null;
                sentToolState = null;
                cheatsAllowed = false;
                // Start looking through the eyes, in both games.
                viewMode = 0;
                Send("VIEW 0");
                // Minecraft sends all its blocks again from scratch.
                RemoveAllBlocks();
                Logger.LogInfo("Connected to Minecraft");
                return;
            }

            if (line == Disconnected)
            {
                ErrorMessage.AddMessage("Minecraft link lost");
                // With Minecraft gone, let Subnautica's food bar and movement work normally again.
                // (Hunger and thirst are switched back on in Update, now that "linked" is about to be false.)
                RemovePlayerLights();
                lastFood = -1f;
                targetTime = -100f;
                hasPlaced = false;
                waitingForMinecraft = false;
                linked = false;
                typing = false;
                equippedTool = null;
                viewMode = 0;
                ReleaseLifepod();

                if (screenOpen)
                {
                    screenOpen = false;
                    FreePointer(false);
                }

                minecraftMenuOpen = false;
                subnauticaMenuNext = false;
                CloseOverlay();
                RemoveAllBlocks();
                Logger.LogInfo("Lost the connection to Minecraft");
                return;
            }

            // Everything else acts on the player, so it needs one who is alive.
            if (player == null || life == null || life.health <= 0f)
            {
                return;
            }

            if (line.StartsWith("HEALTH ", StringComparison.Ordinal))
            {
                float fraction;
                if (float.TryParse(line.Substring(7), NumberStyles.Float, CultureInfo.InvariantCulture, out fraction) && fraction > 0f)
                {
                    float target = Mathf.Clamp01(fraction) * life.maxHealth;

                    if (target < life.health - SmallestChange)
                    {
                        // Going down: deal it as damage first so you get the hit effects.
                        applyingMinecraftDamage = true;

                        try
                        {
                            life.TakeDamage(life.health - target, player.transform.position, DamageType.Normal, null);
                        }
                        finally
                        {
                            applyingMinecraftDamage = false;
                        }
                    }

                    if (life.health > 0f)
                    {
                        // Then set the exact value, so a reinforced suit can't leave the bars apart.
                        life.health = target;
                    }
                }
            }
            else if (line.StartsWith("FOOD ", StringComparison.Ordinal))
            {
                // "FOOD 0.8": Minecraft's hunger bar is at this level. Follow it.
                Survival survival = player.GetComponent<Survival>();
                float level;

                if (survival != null && float.TryParse(line.Substring(5), NumberStyles.Float, CultureInfo.InvariantCulture, out level))
                {
                    lastFood = Mathf.Clamp01(level) * FullStat;
                    survival.food = lastFood;
                }
            }
            else if (line.StartsWith("ATTACK ", StringComparison.Ordinal))
            {
                HandleAttack(line.Substring(7), player);
            }
            else if (line == "DEATH")
            {
                life.Kill(DamageType.Normal);
                lastHealth = life.health;
            }

            // (After a health line, the health Minecraft asked for is remembered, so the
            // comparison in Update() doesn't see it as a change made here and report it back.
            // Only after those: doing it after every line swallowed damage taken in Subnautica
            // whenever some other line happened to arrive in the same frame.)
            if (line.StartsWith("HEALTH ", StringComparison.Ordinal))
            {
                lastHealth = life.health;
            }
        }

        /// <summary>Where the player looks out from: the camera, or failing that roughly head height.</summary>
        private static Vector3 EyePosition(Player player)
        {
            Camera view = Camera.main;
            Vector3 body = player.transform.position;

            if (view == null)
            {
                return body;
            }

            Vector3 eyes = view.transform.position;

            // If the camera is somewhere else entirely (a cutscene, a camera drone), don't use it.
            return (eyes - body).sqrMagnitude < 9f ? eyes : body;
        }

        // ---- Minecraft's hands instead of Subnautica's --------------------------------------------

        /// <summary>Sends number-key presses and scroll-wheel clicks to Minecraft's hotbar.</summary>
        private void SendHotbarKeys()
        {
            bool playing = Application.isFocused && Cursor.lockState == CursorLockMode.Locked && !typing && !screenOpen && !consoleOpen;

            // While Subnautica's battery chooser is up (R with a tool in hand), the scroll wheel
            // and number keys are for choosing a battery, not for Minecraft's hotbar: moving off
            // the tool's token would put the tool away.
            bool choosingBattery = BatteryChooserOpen();

            for (int i = 0; i < 9; i++)
            {
                // In a vehicle the number keys pick its modules instead.
                bool down = playing && !choosingBattery && !ridingNow && KeyHeld(FirstNumberKey + i);

                if (down && !numberKeyWasDown[i])
                {
                    Send("SLOT " + i);
                }

                numberKeyWasDown[i] = down;
            }

            // Q drops the item in Minecraft's hand; with Control held, the whole pile, as in
            // Minecraft. While a Subnautica tool is out, Q is left to Subnautica (the habitat
            // builder uses it to take things apart).
            bool dropDown = playing && equippedTool == null && KeyHeld(0x51);

            if (dropDown && !dropKeyWasDown)
            {
                Send(KeyHeld(0x11) ? "DROP 1" : "DROP 0");
            }

            dropKeyWasDown = dropDown;

            // F swaps what is in Minecraft's two hands, as in Minecraft. Not while one of
            // Subnautica's tools is out: there, F is the tool's own second button.
            bool swapDown = playing && equippedTool == null && KeyHeld(SwapKey);

            if (swapDown && !swapKeyWasDown)
            {
                Send("SWAP");
            }

            swapKeyWasDown = swapDown;

            if (!cycleLookupDone)
            {
                cycleLookupDone = true;

                try
                {
                    cycleNext = (GameInput.Button)Enum.Parse(typeof(GameInput.Button), "CycleNext");
                    cyclePrev = (GameInput.Button)Enum.Parse(typeof(GameInput.Button), "CyclePrev");
                    cycleFound = true;
                }
                catch (Exception)
                {
                    Logger.LogWarning("Could not find Subnautica's scroll controls, so the scroll wheel won't move Minecraft's hotbar");
                }
            }

            if (cycleFound && playing && !choosingBattery)
            {
                // Subnautica's "next" is the scroll direction Minecraft treats as "previous".
                // (In a vehicle Subnautica itself is told the wheel hasn't moved, so this mod's own
                // question is marked as such.)
                ownButtonCheck = true;

                try
                {
                    if (GameInput.GetButtonDown(cycleNext))
                    {
                        Send("SCROLL -1");
                    }

                    if (GameInput.GetButtonDown(cyclePrev))
                    {
                        Send("SCROLL 1");
                    }
                }
                finally
                {
                    ownButtonCheck = false;
                }
            }
        }

        /// <summary>Subnautica's quick slots (its tool bar), fetched by name so a game update gives a warning rather than a crash.</summary>
        private object QuickSlots()
        {
            const BindingFlags Any = BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic;
            Inventory inventory = Inventory.main;

            if (inventory == null)
            {
                return null;
            }

            PropertyInfo property = inventory.GetType().GetProperty("quickSlots", Any);
            FieldInfo field = inventory.GetType().GetField("quickSlots", Any);
            return property != null ? property.GetValue(inventory, null) : field != null ? field.GetValue(inventory) : null;
        }

        /// <summary>
        /// Lists everything in Subnautica's inventory that can be held and used as a tool, with
        /// the game's own name for each on screen. Each entry is the inventory item itself (which
        /// the quick slots need) and its two names.
        /// </summary>
        private List<KeyValuePair<object, string[]>> FindTools()
        {
            List<KeyValuePair<object, string[]>> tools = new List<KeyValuePair<object, string[]>>();
            const BindingFlags Any = BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic;
            Inventory inventory = Inventory.main;

            if (inventory == null)
            {
                return tools;
            }

            PropertyInfo containerProperty = inventory.GetType().GetProperty("container", Any);
            System.Collections.IEnumerable container = containerProperty != null ? containerProperty.GetValue(inventory, null) as System.Collections.IEnumerable : null;

            if (container == null)
            {
                if (warnedMissing.Add("container"))
                {
                    Logger.LogWarning("Could not read Subnautica's inventory, so its tools can't be offered in Minecraft");
                }

                return tools;
            }

            foreach (object entry in container)
            {
                if (entry == null)
                {
                    continue;
                }

                PropertyInfo itemProperty = entry.GetType().GetProperty("item", Any);
                Pickupable item = itemProperty != null ? itemProperty.GetValue(entry, null) as Pickupable : null;

                // A tool is anything with the game's "PlayerTool" part. Caught fish count as
                // tools in Subnautica (you can hold them), but they aren't wanted here.
                if (item == null || item.GetComponent<PlayerTool>() == null || item.GetComponent<Creature>() != null)
                {
                    continue;
                }

                string internalName = item.GetTechType().ToString();
                tools.Add(new KeyValuePair<object, string[]>(entry, new[] { internalName, DisplayName(internalName) }));
            }

            return tools;
        }

        /// <summary>The name Subnautica shows for an item ("Survival Knife" for "Knife"), or the internal name if that can't be found.</summary>
        private string DisplayName(string internalName)
        {
            try
            {
                Type language = typeof(Player).Assembly.GetType("Language");
                const BindingFlags AnyStatic = BindingFlags.Static | BindingFlags.Public | BindingFlags.NonPublic;
                object main = null;

                if (language != null)
                {
                    PropertyInfo property = language.GetProperty("main", AnyStatic);
                    FieldInfo field = language.GetField("main", AnyStatic);
                    main = property != null ? property.GetValue(null, null) : field != null ? field.GetValue(null) : null;
                }

                MethodInfo get = main != null ? language.GetMethod("Get", new[] { typeof(string) }) : null;
                string name = get != null ? get.Invoke(main, new object[] { internalName }) as string : null;

                // These two characters separate the entries in the message to Minecraft.
                if (!string.IsNullOrEmpty(name))
                {
                    return name.Replace('|', ' ').Replace(':', ' ');
                }
            }
            catch (Exception)
            {
                // Fall through to the internal name.
            }

            return internalName;
        }

        /// <summary>Tells Minecraft which tools are in Subnautica's inventory ("TOOLS Knife:Survival Knife:1|...", the last number being how many are carried), when the list changes.</summary>
        private void SendTools()
        {
            try
            {
                // How many of each kind of tool are carried, in the order they are met.
                List<string[]> kinds = new List<string[]>();
                Dictionary<string, int> counts = new Dictionary<string, int>();

                foreach (KeyValuePair<object, string[]> tool in FindTools())
                {
                    int count;

                    if (counts.TryGetValue(tool.Value[0], out count))
                    {
                        counts[tool.Value[0]] = count + 1;
                    }
                    else
                    {
                        counts[tool.Value[0]] = 1;
                        kinds.Add(tool.Value);
                    }
                }

                StringBuilder list = new StringBuilder();

                foreach (string[] kind in kinds)
                {
                    if (list.Length > 0)
                    {
                        list.Append('|');
                    }

                    // Internal name, name to show, how many: Minecraft keeps that many tokens.
                    list.Append(kind[0]).Append(':').Append(kind[1]).Append(':').Append(counts[kind[0]].ToString(CultureInfo.InvariantCulture));
                }

                string text = list.ToString();

                if (text != sentTools)
                {
                    sentTools = text;
                    Send("TOOLS " + text);
                    Logger.LogInfo("Tools offered to Minecraft: " + (text.Length > 0 ? text : "(none)"));
                }
            }
            catch (Exception e)
            {
                if (warnedMissing.Add("tools"))
                {
                    Logger.LogWarning("Could not list Subnautica's tools: " + e);
                }
            }
        }

        /// <summary>
        /// Makes sure the tool Minecraft is holding the token for is the one in Subnautica's
        /// hands. Subnautica only takes out tools that sit in a quick slot, so if the tool isn't
        /// in one, it is put in the first; then that slot is selected.
        /// </summary>
        private void HoldEquippedTool()
        {
            if (equippedTool == null)
            {
                return;
            }

            const BindingFlags Any = BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic;

            try
            {
                Inventory inventory = Inventory.main;
                PlayerTool held = inventory != null ? inventory.GetHeldTool() : null;
                Pickupable heldItem = held != null ? held.GetComponent<Pickupable>() : null;

                if (heldItem != null && heldItem.GetTechType().ToString() == equippedTool)
                {
                    return;
                }

                object wanted = null;

                foreach (KeyValuePair<object, string[]> tool in FindTools())
                {
                    if (tool.Value[0] == equippedTool)
                    {
                        wanted = tool.Key;
                        break;
                    }
                }

                object slots = QuickSlots();

                if (wanted == null || slots == null)
                {
                    return;
                }

                Type type = slots.GetType();
                MethodInfo find = type.GetMethod("GetSlotByItem", Any);
                int slot = find != null ? (int)find.Invoke(slots, new[] { wanted }) : -1;

                if (slot < 0)
                {
                    MethodInfo bind = type.GetMethod("Bind", Any);

                    if (bind != null)
                    {
                        bind.Invoke(slots, new[] { (object)0, wanted });
                        slot = 0;
                    }
                }

                MethodInfo select = type.GetMethod("SelectImmediate", Any, null, new[] { typeof(int) }, null)
                    ?? type.GetMethod("Select", Any, null, new[] { typeof(int) }, null);

                if (slot >= 0 && select != null)
                {
                    select.Invoke(slots, new object[] { slot });
                }
                else if (warnedMissing.Add("equip"))
                {
                    Logger.LogWarning("Could not take out Subnautica's " + equippedTool + ": slot " + slot + ", select method " + (select != null ? "found" : "missing"));
                }
            }
            catch (Exception e)
            {
                if (warnedMissing.Add("equip error"))
                {
                    Logger.LogWarning("Could not take out Subnautica's " + equippedTool + ": " + e);
                }
            }
        }

        /// <summary>
        /// Keeps Subnautica's hands empty: if a tool has been taken out (the number keys still
        /// reach Subnautica), it is put straight back.
        ///
        /// Not called while Minecraft is holding the token for a tool (see HoldEquippedTool).
        /// </summary>
        private void PutAwayTool()
        {
            const BindingFlags Any = BindingFlags.Instance | BindingFlags.Static | BindingFlags.Public | BindingFlags.NonPublic;

            try
            {
                Inventory inventory = Inventory.main;

                if (inventory == null || inventory.GetHeldTool() == null)
                {
                    return;
                }

                // Inventory.main.quickSlots.DeselectImmediate(), looked up by name so a game
                // update that changes it gives a warning instead of stopping the mod loading.
                Type type = inventory.GetType();
                object slots = null;
                PropertyInfo property = type.GetProperty("quickSlots", Any);
                FieldInfo field = type.GetField("quickSlots", Any);

                if (property != null)
                {
                    slots = property.GetValue(inventory, null);
                }
                else if (field != null)
                {
                    slots = field.GetValue(inventory);
                }

                MethodInfo deselect = slots != null ? slots.GetType().GetMethod("DeselectImmediate", Any, null, Type.EmptyTypes, null) : null;

                if (deselect != null)
                {
                    deselect.Invoke(slots, null);
                }
                else if (warnedMissing.Add("quickSlots"))
                {
                    Logger.LogWarning("Could not find how to put Subnautica's tool away; its tools will still work, unseen");
                }
            }
            catch (Exception e)
            {
                if (warnedMissing.Add("quickSlots error"))
                {
                    Logger.LogWarning("Could not put Subnautica's tool away: " + e.Message);
                }
            }
        }

        /// <summary>
        /// Makes Subnautica's character (arms, body, anything in its hands) invisible by
        /// switching off everything that draws it. Runs every frame because the game switches
        /// parts back on by itself. Nothing is removed, so it can all be switched back on.
        /// </summary>
        private void HideBody(Player player)
        {
            player.GetComponentsInChildren(true, bodyScratch);

            foreach (Renderer part in bodyScratch)
            {
                // With a tool in hand, only its solid shape (and the arms) are hidden. Its
                // effects (beams, sparks, trails) are left showing, so you can see it working.
                bool effect = !(part is MeshRenderer || part is SkinnedMeshRenderer);

                if (effect && equippedTool != null)
                {
                    if (hiddenBody.Remove(part))
                    {
                        part.enabled = true;
                    }

                    continue;
                }

                if (part.enabled)
                {
                    part.enabled = false;
                    hiddenBody.Add(part);
                }
            }

            // Something that has left the player (an item dropped, a beacon or vehicle bay
            // put out) is no longer part of the body: it is shown again and forgotten.
            if (Time.unscaledTime >= nextBodyCheck)
            {
                nextBodyCheck = Time.unscaledTime + 0.5f;
                Transform body = player.transform;

                hiddenBody.RemoveWhere(part =>
                {
                    if (part == null)
                    {
                        return true;
                    }

                    if (part.transform.IsChildOf(body))
                    {
                        return false;
                    }

                    part.enabled = true;
                    return true;
                });
            }

            // Tools and other parts get destroyed over time; don't remember them forever.
            if (hiddenBody.Count > 2000)
            {
                hiddenBody.RemoveWhere(part => part == null);
            }
        }

        private float nextBodyCheck;

        private void ShowBody()
        {
            if (hiddenBody.Count == 0)
            {
                return;
            }

            foreach (Renderer part in hiddenBody)
            {
                // A part may have been destroyed since.
                if (part != null)
                {
                    part.enabled = true;
                }
            }

            hiddenBody.Clear();
        }

        // ---- Minecraft's HUD ------------------------------------------------------------------

        /// <summary>Each frame: fetch the newest HUD picture if there is one, and keep Subnautica's bars in step.</summary>
        private void UpdateOverlay()
        {
            if (linked)
            {
                // Tell Minecraft how big this screen is, so it draws its HUD at the same size.
                if (Screen.width != sentScreenWidth || Screen.height != sentScreenHeight)
                {
                    sentScreenWidth = Screen.width;
                    sentScreenHeight = Screen.height;
                    Send("SCREEN " + Screen.width + " " + Screen.height);
                }

                if (overlayMemory == IntPtr.Zero && overlayPath != null && Time.unscaledTime >= nextOverlayOpenTry)
                {
                    // Minecraft creates the file when it first draws a frame while linked, so
                    // this can take a few tries.
                    nextOverlayOpenTry = Time.unscaledTime + 1f;
                    OpenOverlay();
                }

                if (overlayMemory != IntPtr.Zero)
                {
                    ReadOverlayFrame();
                }
            }

            if (linked && Time.unscaledTime >= nextLifepodCheck)
            {
                nextLifepodCheck = Time.unscaledTime + 1f;
                HoldLifepodStill();
            }

            if (linked && Player.main != null && Time.unscaledTime >= nextToolCheck)
            {
                nextToolCheck = Time.unscaledTime + 0.5f;
                SendTools();
                HoldEquippedTool();
            }

            linkedNow = linked;
            pdaOpen = linked && Player.main != null && PdaOpen(Player.main);
            blockSubnauticaTools = OverlayShowing();

            // Subnautica's bars are hidden exactly while Minecraft's HUD is showing.
            if (Time.unscaledTime >= nextBarCheck)
            {
                nextBarCheck = Time.unscaledTime + 1f;

                if (OverlayShowing())
                {
                    HideBars();
                }
                else
                {
                    ShowBars();
                }
            }
        }

        /// <summary>
        /// Stops the lifepod bobbing and drifting while linked. Minecraft's blocks stay exactly
        /// where they are put, so a lifepod that moves would drift through them, and its floor
        /// rocking under the player's feet makes standing in it shaky. The lifepod is set upright
        /// and its physics body is switched to "kinematic", which means physics no longer moves it.
        /// Checked once a second in case the game switches it back.
        /// </summary>
        private void HoldLifepodStill()
        {
            try
            {
                Type type = typeof(Player).Assembly.GetType("EscapePod");

                if (type == null)
                {
                    if (warnedMissing.Add("EscapePod"))
                    {
                        Logger.LogWarning("Could not find the lifepod in this version of Subnautica, so it keeps moving");
                    }

                    return;
                }

                // The game keeps the lifepod in "EscapePod.main".
                const BindingFlags Any = BindingFlags.Static | BindingFlags.Public | BindingFlags.NonPublic;
                object value = null;
                FieldInfo field = type.GetField("main", Any);
                PropertyInfo property = type.GetProperty("main", Any);

                if (field != null)
                {
                    value = field.GetValue(null);
                }
                else if (property != null)
                {
                    value = property.GetValue(null, null);
                }

                Component pod = value as Component;

                if (pod == null)
                {
                    return;
                }

                Rigidbody body = pod.GetComponent<Rigidbody>();

                if (body == null || body.isKinematic)
                {
                    return;
                }

                body.velocity = Vector3.zero;
                body.angularVelocity = Vector3.zero;
                body.isKinematic = true;

                // Upright, keeping the way it faces, so its floor is level.
                pod.transform.rotation = Quaternion.Euler(0f, pod.transform.eulerAngles.y, 0f);

                if (heldLifepod != body)
                {
                    heldLifepod = body;
                    Vector3 spot = pod.transform.position;
                    Logger.LogInfo(string.Format(CultureInfo.InvariantCulture, "Holding the lifepod still at ({0:0.#}, {1:0.#}, {2:0.#})", spot.x, spot.y, spot.z));
                }
            }
            catch (Exception e)
            {
                if (warnedMissing.Add("EscapePod error"))
                {
                    Logger.LogWarning("Could not hold the lifepod still: " + e.Message);
                }
            }
        }

        /// <summary>Lets the lifepod float freely again, and gives the player back to Subnautica's physics (when the link ends).</summary>
        private void ReleaseLifepod()
        {
            if (heldPlayerBody != null)
            {
                // Not while seated in a vehicle or at the Cyclops's helm: Subnautica holds the
                // body there itself, and letting physics have it would drop it out of the seat.
                bool seatedNow = false;

                try
                {
                    seatedNow = Player.main != null && (Player.main.GetVehicle() != null || Player.main.isPiloting);
                }
                catch (Exception)
                {
                    seatedNow = false;
                }

                if (!seatedNow)
                {
                    heldPlayerBody.isKinematic = false;
                }
            }

            heldPlayerBody = null;

            if (heldLifepod != null)
            {
                heldLifepod.isKinematic = false;
            }

            heldLifepod = null;
        }

        /// <summary>
        /// Shows and frees the mouse pointer (for clicking on a Minecraft screen), or hides it and
        /// locks it back to the middle of the screen (for looking around). Subnautica has its own
        /// setting for this, which is told as well so the two don't fight.
        /// </summary>
        private void FreePointer(bool free)
        {
            try
            {
                if (!lockCursorLookupDone)
                {
                    lockCursorLookupDone = true;
                    Type utils = typeof(Player).Assembly.GetType("UWE.Utils");

                    foreach (Assembly assembly in AppDomain.CurrentDomain.GetAssemblies())
                    {
                        if (utils != null)
                        {
                            break;
                        }

                        utils = assembly.GetType("UWE.Utils");
                    }

                    lockCursorSetting = utils != null ? utils.GetProperty("lockCursor", BindingFlags.Static | BindingFlags.Public | BindingFlags.NonPublic) : null;

                    if (lockCursorSetting == null)
                    {
                        Logger.LogWarning("Could not find Subnautica's pointer setting; the pointer may stick while a Minecraft screen is open");
                    }
                }

                if (lockCursorSetting != null && lockCursorSetting.CanWrite)
                {
                    lockCursorSetting.SetValue(null, !free, null);
                }
            }
            catch (Exception)
            {
                // Fall back to Unity's own setting below.
            }

            Cursor.lockState = free ? CursorLockMode.None : CursorLockMode.Locked;
            Cursor.visible = free;
        }

        /// <summary>
        /// Sends where the mouse pointer is in this window ("POINTER x y", as fractions of the
        /// window from its top left corner) and any change in the mouse buttons ("CLICK button
        /// down"; button 0 is left and 1 is right). Read straight from Windows.
        /// </summary>
        private void SendPointer()
        {
            if (!Application.isFocused)
            {
                return;
            }

            IntPtr window = GetActiveWindow();
            ScreenPoint point;
            ScreenRect area;

            if (window == IntPtr.Zero || !GetCursorPos(out point) || !ScreenToClient(window, ref point) || !GetClientRect(window, out area)
                || area.right <= 0 || area.bottom <= 0)
            {
                return;
            }

            float x = Mathf.Clamp01(point.x / (float)area.right);
            float y = Mathf.Clamp01(point.y / (float)area.bottom);

            if (Mathf.Abs(x - sentPointerX) > 0.0002f || Mathf.Abs(y - sentPointerY) > 0.0002f)
            {
                sentPointerX = x;
                sentPointerY = y;
                Send(string.Format(CultureInfo.InvariantCulture, "POINTER {0:0.####} {1:0.####}", x, y));
            }

            // Clicks are for screens with slots in them, not the chat box.
            if (typing)
            {
                return;
            }

            // The scroll wheel, for screens that scroll (the Creative inventory's lists).
            float wheel = WheelTurned();

            if (wheel != 0f)
            {
                Send("WHEEL " + wheel.ToString("0.###", CultureInfo.InvariantCulture));
            }

            for (int button = 0; button < 2; button++)
            {
                bool down = KeyHeld(button == 0 ? LeftMouseButton : RightMouseButton);

                // A press on a panel of this mod's own drawn over the screen is that panel's, not
                // Minecraft's: neither it nor the letting go that follows is passed on.
                if (panelButtons[button])
                {
                    if (!down)
                    {
                        panelButtons[button] = false;
                        sentButtons[button] = false;
                    }

                    continue;
                }

                if (down && !sentButtons[button] && menuPanelShown && menuPanel.Contains(new Vector2(point.x, point.y)))
                {
                    panelButtons[button] = true;
                    sentButtons[button] = true;
                    continue;
                }

                if (down != sentButtons[button])
                {
                    sentButtons[button] = down;
                    // The third number says whether Shift is held, for shift-clicking items.
                    Send("CLICK " + button + (down ? " 1" : " 0") + (KeyHeld(0x10) ? " 1" : " 0"));
                }
            }
        }

        /// <summary>
        /// Typing commands into Minecraft. Pressing "/" while playing opens Minecraft's chat box;
        /// from then until Enter or Escape, every key pressed is sent to Minecraft instead of
        /// doing anything in Subnautica. Characters go as "TYPE number"; other keys go as
        /// "KEY number", using the numbers Minecraft's window system has for them.
        /// </summary>
        private void HandleTyping(Event key)
        {
            // Subnautica's own command console is being typed into: every key is its.
            if (consoleOpen)
            {
                return;
            }

            if (!typing)
            {
                if (screenOpen)
                {
                    // A Minecraft screen other than chat is open (the inventory). E or Escape
                    // closes it: Minecraft's screens all close on its Escape key, number 256.
                    if (key.keyCode == KeyCode.E || key.keyCode == KeyCode.Escape)
                    {
                        Send("KEY 256");
                        key.Use();
                    }
                    else if (key.keyCode == KeyCode.F)
                    {
                        // Minecraft's key for swapping the item under the pointer with the
                        // off hand: number 70 in its window system.
                        Send("KEY 70");
                        key.Use();
                    }

                    return;
                }

                // Not while Subnautica's PDA is open: there, E and "/" are left alone.
                bool playing = linked && OverlayShowing() && !pdaOpen && !consoleOpen && Application.isFocused && Cursor.lockState == CursorLockMode.Locked;

                if (playing && key.character == '/')
                {
                    typing = true;
                    Send("CHAT /");
                    key.Use();
                }
                else if (playing && key.keyCode == KeyCode.E)
                {
                    Send("INVENTORY");
                    key.Use();
                }

                return;
            }

            // Unity reports a key press and the character it types as two separate events.
            if (key.character != 0)
            {
                // Enter and Tab also arrive as characters; they are handled as keys below.
                if (key.character >= ' ')
                {
                    Send("TYPE " + (int)key.character);
                }

                key.Use();
                return;
            }

            int code = 0;

            switch (key.keyCode)
            {
                case KeyCode.Return:
                case KeyCode.KeypadEnter: code = 257; typing = false; break;
                case KeyCode.Escape: code = 256; typing = false; break;
                case KeyCode.Tab: code = 258; break;
                case KeyCode.Backspace: code = 259; break;
                case KeyCode.Delete: code = 261; break;
                case KeyCode.RightArrow: code = 262; break;
                case KeyCode.LeftArrow: code = 263; break;
                case KeyCode.DownArrow: code = 264; break;
                case KeyCode.UpArrow: code = 265; break;
                case KeyCode.Home: code = 268; break;
                case KeyCode.End: code = 269; break;
            }

            if (code != 0)
            {
                Send("KEY " + code);
            }

            key.Use();
        }

        private bool OverlayShowing()
        {
            return linked && overlayTexture != null && Time.unscaledTime - overlayFrameTime < 1f;
        }

        /// <summary>
        /// Unity calls this when it is time to draw simple on-screen graphics, after the game's
        /// own picture and menus are done. Minecraft's HUD is stretched over the whole screen;
        /// its see-through parts let Subnautica show through.
        /// </summary>
        private void OnGUI()
        {
            if (Event.current.type == EventType.KeyDown)
            {
                HandleTyping(Event.current);
                return;
            }

            if (Event.current.type == EventType.Repaint && OverlayShowing() && !pdaOpen)
            {
                GUI.DrawTexture(new Rect(0f, 0f, Screen.width, Screen.height), overlayTexture, ScaleMode.StretchToFill, true);
            }

            // With Subnautica's Fabricator open: the panel of Minecraft materials it can make.
            if (linked && atFabricator)
            {
                DrawTrades();
            }

            // With either game's menu up: the panel that swaps between the two.
            DrawMenuSwap();
        }

        // ---- Swapping between the two games' menus -----------------------------------------------
        //
        // Escape opens Subnautica's own menu. While it is open, a small panel at the left of the
        // screen offers Minecraft's menu instead (for its options, and for what other Minecraft
        // mods put there, such as inviting friends to a hosted world); and while Minecraft's is
        // open, the same panel offers Subnautica's back.
        //
        // Going to Minecraft's: Subnautica's menu is closed and Minecraft is asked to open its
        // own ("MENU 1"), which arrives as a Minecraft screen like any other ("MCSCREEN 1"),
        // drawn in Minecraft's picture and clicked on through "POINTER" and "CLICK".
        // Going back: Minecraft is asked to close whatever it has open ("MENU 0"), and once it
        // says it has ("MCSCREEN 0"), Subnautica's menu is opened again.
        //
        // If Subnautica's menu had stopped the game (it does when playing alone), the game is
        // kept stopped under Minecraft's menu too, and let go when that menu is closed.

        /// <summary>True from asking Minecraft for its menu until that screen closes.</summary>
        private bool minecraftMenuOpen;

        /// <summary>True from asking Minecraft to close its menu until it has: Subnautica's is opened then.</summary>
        private bool subnauticaMenuNext;

        /// <summary>When Minecraft was last asked to open or close its menu. An ask it never acts on is forgotten after a moment.</summary>
        private float menuAskedAt;

        /// <summary>Whether Subnautica's menu had the game stopped when Minecraft's was asked for.</summary>
        private bool menuHoldsTime;

        /// <summary>True while this mod is the one keeping the game stopped.</summary>
        private bool menuStoppedTime;

        private GUIStyle menuBox;
        private GUIStyle menuButton;
        private int menuStyleSize;

        /// <summary>Whether Subnautica's own in-game menu (the one Escape opens) is up.</summary>
        private static bool SubnauticaMenuOpen()
        {
            try
            {
                IngameMenu menu = IngameMenu.main;
                return menu != null && menu.selected;
            }
            catch (Exception)
            {
                return false;
            }
        }

        /// <summary>Every frame: forget asks Minecraft never acted on, and keep the game stopped under Minecraft's menu if it was stopped under Subnautica's.</summary>
        private void TendMenuSwap()
        {
            if (!linked)
            {
                minecraftMenuOpen = false;
                subnauticaMenuNext = false;
            }

            // Asked for Minecraft's menu and no screen came (Minecraft had no player just then, say).
            if (minecraftMenuOpen && !screenOpen && Time.unscaledTime - menuAskedAt > 1.5f)
            {
                minecraftMenuOpen = false;
            }

            // Asked Minecraft to close its menu and it never said it had.
            if (subnauticaMenuNext && Time.unscaledTime - menuAskedAt > 1.5f)
            {
                subnauticaMenuNext = false;
            }

            // Only while Minecraft's screen is really up. (Subnautica remembers how fast time was
            // going whenever something of its own stops it, to put it back afterwards; so it
            // must never find the game stopped by this mod at such a moment. With a Minecraft
            // screen open, Subnautica's menu key is switched off and nothing of its own can.)
            bool wanted = menuHoldsTime && (minecraftMenuOpen || subnauticaMenuNext);

            if (wanted && screenOpen)
            {
                if (Time.timeScale != 0f)
                {
                    Time.timeScale = 0f;
                    menuStoppedTime = true;
                }
            }
            else
            {
                if (!wanted)
                {
                    menuHoldsTime = false;
                }

                if (menuStoppedTime)
                {
                    menuStoppedTime = false;

                    // Let the game go again, unless Subnautica's own menu is up and has it stopped itself.
                    if (Time.timeScale == 0f && !SubnauticaMenuOpen())
                    {
                        Time.timeScale = 1f;
                    }
                }
            }
        }

        /// <summary>What Minecraft was last told about holding its player still: 1 yes, 0 no, -1 nothing yet.</summary>
        private int sentHold = -1;

        /// <summary>
        /// Every frame: while Subnautica is stopped (its menu is up, playing alone; or this mod is
        /// keeping it stopped under Minecraft's menu), Minecraft is told to hold its player
        /// where they are ("HOLD 1"), and to let them go when it starts again ("HOLD 0").
        /// Otherwise Minecraft, which never stops, went on moving them through a frozen world.
        /// </summary>
        private void TendHold()
        {
            if (!linked)
            {
                sentHold = -1;
                return;
            }

            int hold = Time.timeScale == 0f ? 1 : 0;

            if (hold != sentHold)
            {
                sentHold = hold;
                Send("HOLD " + hold);
            }
        }

        /// <summary>
        /// Slotted in front of the part of Subnautica that closes whatever menu is up when the
        /// mouse is pressed on empty screen beside it. With its game menu up while linked, that
        /// press does nothing (returning false skips the closing): the panel this mod draws beside
        /// the menu is "empty screen" to Subnautica, and clicking it closed the menu.
        /// The menu's own buttons, and Escape, close it as before.
        /// </summary>
        public static bool KeepGameMenu(uGUI_InputGroup __0)
        {
            // Only a change to "no menu at all", made while a mouse button is going down.
            if (__0 != null || !linkedNow || !(KeyHeld(LeftMouseButton) || KeyHeld(RightMouseButton) || KeyHeld(MiddleMouseButton)))
            {
                return true;
            }

            return !SubnauticaMenuOpen();
        }

        /// <summary>The panel at the left of the screen, shown while either game's menu is up.</summary>
        private void DrawMenuSwap()
        {
            menuPanelShown = false;

            if (!linked)
            {
                return;
            }

            bool subnauticas = SubnauticaMenuOpen();
            bool minecrafts = screenOpen && minecraftMenuOpen && !subnauticaMenuNext;

            if (!subnauticas && !minecrafts)
            {
                return;
            }

            // Sized for a 1080-line screen and scaled to whatever the screen is.
            float scale = Screen.height / 1080f;
            int textSize = Mathf.Max(10, Mathf.RoundToInt(17f * scale));

            if (menuBox == null || menuStyleSize != textSize)
            {
                menuStyleSize = textSize;
                menuBox = new GUIStyle(GUI.skin.box) { fontSize = textSize, alignment = TextAnchor.UpperCenter };
                menuButton = new GUIStyle(GUI.skin.button) { fontSize = textSize, alignment = TextAnchor.MiddleCenter };
            }

            float width = 230f * scale;
            float row = 44f * scale;
            float gap = 8f * scale;
            float height = 40f * scale + 2f * (row + gap);
            Rect panel = new Rect(30f * scale, (Screen.height - height) / 2f, width, height);

            // Clicks here are this panel's, not for the Minecraft screen underneath (see SendPointer).
            menuPanel = panel;
            menuPanelShown = true;

            GUI.Box(panel, "Menu", menuBox);

            Rect first = new Rect(panel.x + gap, panel.y + 40f * scale, panel.width - 2f * gap, row);
            Rect second = new Rect(first.x, first.y + row + gap, first.width, row);

            // The one that is showing is greyed out; the other is the one to click.
            GUI.enabled = !subnauticas;

            if (GUI.Button(first, "Subnautica", menuButton))
            {
                ShowSubnauticaMenu();
            }

            GUI.enabled = !minecrafts;

            if (GUI.Button(second, "Minecraft", menuButton))
            {
                ShowMinecraftMenu();
            }

            GUI.enabled = true;
        }

        /// <summary>From Subnautica's menu to Minecraft's.</summary>
        private void ShowMinecraftMenu()
        {
            try
            {
                IngameMenu menu = IngameMenu.main;

                if (menu == null || !menu.CanClose())
                {
                    return;
                }

                bool stopped = Time.timeScale == 0f;
                menu.Close();

                // It didn't close (something else of Subnautica's had hold of the screen): leave things as they are.
                if (menu.selected)
                {
                    return;
                }

                menuHoldsTime = stopped;
                minecraftMenuOpen = true;
                subnauticaMenuNext = false;
                menuAskedAt = Time.unscaledTime;
                Send("MENU 1");
            }
            catch (Exception e)
            {
                Logger.LogWarning("Could not swap to Minecraft's menu: " + e.Message);
            }
        }

        /// <summary>From Minecraft's menu back to Subnautica's: asked for here, done when Minecraft says its screen has closed.</summary>
        private void ShowSubnauticaMenu()
        {
            subnauticaMenuNext = true;
            menuAskedAt = Time.unscaledTime;
            Send("MENU 0");
        }

        /// <summary>Minecraft's menu has closed. Opens Subnautica's, if that is what it was closed for.</summary>
        private void AfterMinecraftMenu()
        {
            bool next = subnauticaMenuNext;
            minecraftMenuOpen = false;
            subnauticaMenuNext = false;

            if (!next)
            {
                return;
            }

            try
            {
                IngameMenu menu = IngameMenu.main;

                if (menu != null && !SubnauticaMenuOpen())
                {
                    // Time goes again first: Subnautica's menu stops it for itself as it opens,
                    // and puts back whatever it found, which must not be this mod's stop.
                    if (menuStoppedTime)
                    {
                        menuStoppedTime = false;
                        Time.timeScale = 1f;
                    }

                    menuHoldsTime = false;
                    menu.Open();
                }
            }
            catch (Exception e)
            {
                Logger.LogWarning("Could not open Subnautica's menu again: " + e.Message);
            }
        }

        // ---- Minecraft materials from the Fabricator -------------------------------------------
        //
        // While the player has a Fabricator open, a small panel at the right of the screen
        // offers to turn a few Subnautica materials into Minecraft ones. Clicking one takes the
        // material from Subnautica's inventory (the same way crafting does) and tells Minecraft
        // "TRADE which howMany", which puts the result in Minecraft's inventory. The same list,
        // in the same order, is in the Minecraft mod (Gathering.java).

        private static readonly TechType[] TradeTakes = { TechType.CreepvineSeedCluster, TechType.CrashPowder, TechType.Titanium, TechType.AluminumOxide, TechType.CreepvinePiece, TechType.Quartz, TechType.CreepvineSeedCluster };
        private static readonly string[] TradeTakesNames = { "Creepvine Seed Cluster", "Cave Sulfur", "Titanium", "Ruby", "Creepvine Sample", "Quartz", "Creepvine Seed Cluster" };
        private static readonly string[] TradeGivesNames = { "4 Oak Logs", "3 Gunpowder", "1 Iron Ingot", "1 Diamond", "8 Sugar Cane", "3 Lapis Lazuli", "1 Oak Sapling" };

        // And the other way round: a Minecraft material handed over for a Subnautica one. This
        // side can't see Minecraft's inventory, so it asks ("BUY which howMany"); Minecraft takes
        // what the player really has and answers "GIVE name howMany". The row comes after the others.
        private static readonly string[] BuyRows = { "1 Iron Ingot (Minecraft)  ->  1 Titanium" };

        /// <summary>True while the player has a Fabricator open.</summary>
        private bool atFabricator;

        /// <summary>Which of the number keys 1 to 6 were down last frame, to spot one being pressed at the Fabricator.</summary>
        private readonly bool[] tradeKeyWasDown = new bool[8];

        // Whether the last frame of controls was sent while playing, and whether each mouse
        // button has been held since before then (see where the controls are sent).
        private bool wasPlaying;
        private bool leftFromMenu;
        private bool rightFromMenu;

        /// <summary>When the pointer was last over the panel, and when it was last over it with the button down.</summary>
        private static float overTradesTime = -100f;
        private static float tradeClickTime = -100f;
        private float nextFabricatorCheck;
        private FieldInfo crafterOpened;
        private bool crafterOpenedLookupDone;
        private GUIStyle tradeBox;
        private GUIStyle tradeButton;
        private GUIStyle tradeNote;
        private int tradeStyleSize;

        /// <summary>A few times a second: is the player at an open Fabricator?</summary>
        private void WatchFabricators(Player player)
        {
            if (Time.unscaledTime < nextFabricatorCheck)
            {
                return;
            }

            nextFabricatorCheck = Time.unscaledTime + 0.25f;
            atFabricator = false;

            // The Fabricator's menu sets the mouse pointer free; while it is steering the camera, no menu is open.
            if (!linked || player == null || Cursor.lockState == CursorLockMode.Locked || screenOpen)
            {
                return;
            }

            try
            {
                if (!crafterOpenedLookupDone)
                {
                    crafterOpenedLookupDone = true;
                    crafterOpened = typeof(GhostCrafter).GetField("_opened", BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic);

                    if (crafterOpened == null)
                    {
                        Logger.LogWarning("Could not find how Subnautica marks a Fabricator as open, so Minecraft materials can't be made at it");
                    }
                }

                if (crafterOpened == null)
                {
                    return;
                }

                foreach (Fabricator fabricator in FindObjectsOfType<Fabricator>())
                {
                    if ((bool)crafterOpened.GetValue(fabricator) && (fabricator.transform.position - player.transform.position).sqrMagnitude < 36f)
                    {
                        atFabricator = true;
                        return;
                    }
                }
            }
            catch (Exception e)
            {
                if (warnedMissing.Add("fabricator"))
                {
                    Logger.LogWarning("Could not check for an open Fabricator: " + e.Message);
                }
            }
        }

        /// <summary>With the Fabricator open, the number keys 1 to 6 do the same as clicking the rows of the panel.</summary>
        private void WatchTradeKeys()
        {
            Inventory inventory = Inventory.main;
            bool ready = linked && atFabricator && inventory != null && Application.isFocused && !typing && !screenOpen;

            for (int i = 0; i < tradeKeyWasDown.Length; i++)
            {
                bool down = ready && KeyHeld(FirstNumberKey + i);

                if (down && !tradeKeyWasDown[i] && i >= TradeTakes.Length)
                {
                    // One of the rows that go the other way.
                    if (i - TradeTakes.Length < BuyRows.Length)
                    {
                        Send("BUY " + (i - TradeTakes.Length) + " " + (KeyHeld(ShiftKey) ? 64 : 1));
                    }
                }
                else if (down && !tradeKeyWasDown[i])
                {
                    int have = inventory.GetPickupCount(TradeTakes[i]);

                    if (have > 0)
                    {
                        MakeTrade(inventory, i, KeyHeld(ShiftKey) ? Mathf.Min(have, 64) : 1);
                    }
                }

                tradeKeyWasDown[i] = down;
            }
        }

        /// <summary>
        /// Slotted in front of the parts of Subnautica that close a menu when you click away
        /// from it. A click on this mod's panel is not "away": returning false skips the closing.
        /// </summary>
        public static bool KeepFabricatorMenu()
        {
            float now = Time.unscaledTime;
            bool onPanel = (now - overTradesTime < 0.3f && KeyHeld(LeftMouseButton)) || now - tradeClickTime < 0.25f;
            return !onPanel;
        }

        /// <summary>Draws the panel and acts on clicks. Unity calls OnGUI several times a frame: to draw, and once for each click.</summary>
        private void DrawTrades()
        {
            Inventory inventory = Inventory.main;

            if (inventory == null)
            {
                return;
            }

            // Sized for a 1080-line screen and scaled to whatever the screen is.
            float scale = Screen.height / 1080f;
            int textSize = Mathf.Max(10, Mathf.RoundToInt(17f * scale));

            if (tradeBox == null || tradeStyleSize != textSize)
            {
                tradeStyleSize = textSize;
                tradeBox = new GUIStyle(GUI.skin.box) { fontSize = textSize, alignment = TextAnchor.UpperCenter };
                tradeButton = new GUIStyle(GUI.skin.button) { fontSize = textSize, alignment = TextAnchor.MiddleLeft };
                tradeNote = new GUIStyle(GUI.skin.label) { fontSize = Mathf.Max(9, Mathf.RoundToInt(13f * scale)), alignment = TextAnchor.MiddleCenter };
                tradeButton.padding = new RectOffset(Mathf.RoundToInt(12f * scale), Mathf.RoundToInt(12f * scale), 0, 0);
            }

            float width = 460f * scale;
            float row = 44f * scale;
            float gap = 8f * scale;
            float height = 40f * scale + (TradeTakes.Length + BuyRows.Length) * (row + gap) + 30f * scale;
            Rect panel = new Rect(Screen.width - width - 30f * scale, (Screen.height - height) / 2f, width, height);

            // Noted for KeepFabricatorMenu: is the pointer on the panel, and is it being clicked?
            if (panel.Contains(Event.current.mousePosition))
            {
                overTradesTime = Time.unscaledTime;

                if (KeyHeld(LeftMouseButton))
                {
                    tradeClickTime = Time.unscaledTime;
                }
            }

            GUI.Box(panel, "Minecraft materials", tradeBox);

            for (int i = 0; i < TradeTakes.Length; i++)
            {
                int have = inventory.GetPickupCount(TradeTakes[i]);
                Rect button = new Rect(panel.x + gap, panel.y + 40f * scale + i * (row + gap), panel.width - 2f * gap, row);

                GUI.enabled = have > 0;

                if (GUI.Button(button, "[" + (i + 1) + "]  1 " + TradeTakesNames[i] + "  ->  " + TradeGivesNames[i] + "   (have " + have + ")", tradeButton))
                {
                    // Shift turns every one in the inventory at once (up to a pile's worth).
                    MakeTrade(inventory, i, KeyHeld(ShiftKey) ? Mathf.Min(have, 64) : 1);
                }

                GUI.enabled = true;
            }

            for (int i = 0; i < BuyRows.Length; i++)
            {
                int at = TradeTakes.Length + i;
                Rect button = new Rect(panel.x + gap, panel.y + 40f * scale + at * (row + gap), panel.width - 2f * gap, row);

                if (GUI.Button(button, "[" + (at + 1) + "]  " + BuyRows[i], tradeButton))
                {
                    Send("BUY " + i + " " + (KeyHeld(ShiftKey) ? 64 : 1));
                }
            }

            GUI.Label(new Rect(panel.x, panel.yMax - 30f * scale, panel.width, 26f * scale), "Click a row or press its number. Hold Shift to turn all of them.", tradeNote);
        }

        /// <summary>Takes the material from Subnautica's inventory and tells Minecraft how many were taken.</summary>
        private void MakeTrade(Inventory inventory, int which, int times)
        {
            int taken = 0;

            try
            {
                while (taken < times && inventory.DestroyItem(TradeTakes[which], false))
                {
                    taken++;
                }
            }
            catch (Exception e)
            {
                Logger.LogWarning("Could not take " + TradeTakesNames[which] + " from the inventory: " + e.Message);
            }

            if (taken > 0)
            {
                Send("TRADE " + which + " " + taken);
                ErrorMessage.AddMessage(taken + " " + TradeTakesNames[which] + " turned into Minecraft's " + TradeGivesNames[which].Substring(TradeGivesNames[which].IndexOf(' ') + 1));
                Logger.LogInfo("Turned " + taken + " " + TradeTakesNames[which] + " into Minecraft materials");
            }
        }

        private void ReadOverlayFrame()
        {
            try
            {
                if (Marshal.ReadInt32(overlayMemory, 0) != OverlayMarker)
                {
                    return;
                }

                int frame = Marshal.ReadInt32(overlayMemory, 12);

                if (frame == overlayFrame)
                {
                    return;
                }

                int width = Marshal.ReadInt32(overlayMemory, 4);
                int height = Marshal.ReadInt32(overlayMemory, 8);
                int slot = Marshal.ReadInt32(overlayMemory, 16);

                if (width <= 0 || height <= 0 || width > OverlayMaxWidth || height > OverlayMaxHeight || slot < 0 || slot > 1)
                {
                    return;
                }

                if (overlayTexture == null || overlayTexture.width != width || overlayTexture.height != height)
                {
                    if (overlayTexture != null)
                    {
                        Destroy(overlayTexture);
                    }

                    // 4 bytes a pixel: red, green, blue, opacity. Exactly what Minecraft writes.
                    overlayTexture = new Texture2D(width, height, TextureFormat.RGBA32, false);
                    overlayTexture.wrapMode = TextureWrapMode.Clamp;
                    Logger.LogInfo("Showing Minecraft's HUD at " + width + " x " + height);
                }

                IntPtr pixels = new IntPtr(overlayMemory.ToInt64() + OverlayHeaderBytes + (long)slot * OverlaySlotBytes);
                overlayTexture.LoadRawTextureData(pixels, width * height * 4);
                overlayTexture.Apply(false);

                overlayFrame = frame;
                overlayFrameTime = Time.unscaledTime;
            }
            catch (Exception e)
            {
                Logger.LogWarning("Could not read Minecraft's HUD picture: " + e.Message);
                CloseOverlay();
            }
        }

        /// <summary>Opens Minecraft's shared file as a block of this game's memory (read-only).</summary>
        private void OpenOverlay()
        {
            const uint GenericRead = 0x80000000;
            const uint ShareAll = 0x7;          // let Minecraft keep reading, writing and replacing it
            const uint OpenExisting = 3;
            const uint PageReadOnly = 0x02;
            const uint FileMapRead = 0x0004;
            IntPtr invalid = new IntPtr(-1);

            if (!File.Exists(overlayPath) || new FileInfo(overlayPath).Length < OverlayFileBytes)
            {
                return;
            }

            overlayFile = CreateFileW(overlayPath, GenericRead, ShareAll, IntPtr.Zero, OpenExisting, 0, IntPtr.Zero);

            if (overlayFile == invalid || overlayFile == IntPtr.Zero)
            {
                overlayFile = IntPtr.Zero;
                return;
            }

            overlayMapping = CreateFileMappingW(overlayFile, IntPtr.Zero, PageReadOnly, 0, 0, null);

            if (overlayMapping != IntPtr.Zero)
            {
                // 0 bytes means "the whole file".
                overlayMemory = MapViewOfFile(overlayMapping, FileMapRead, 0, 0, UIntPtr.Zero);
            }

            if (overlayMemory == IntPtr.Zero)
            {
                Logger.LogWarning("Could not open Minecraft's HUD file " + overlayPath + " (Windows error " + Marshal.GetLastWin32Error() + ")");
                CloseOverlay();
                // Don't fill the log: try again in ten seconds rather than one.
                nextOverlayOpenTry = Time.unscaledTime + 10f;
                return;
            }

            overlayFrame = -1;
            Logger.LogInfo("Opened Minecraft's HUD file " + overlayPath);
        }

        private void CloseOverlay()
        {
            if (overlayMemory != IntPtr.Zero)
            {
                UnmapViewOfFile(overlayMemory);
                overlayMemory = IntPtr.Zero;
            }

            if (overlayMapping != IntPtr.Zero)
            {
                CloseHandle(overlayMapping);
                overlayMapping = IntPtr.Zero;
            }

            if (overlayFile != IntPtr.Zero)
            {
                CloseHandle(overlayFile);
                overlayFile = IntPtr.Zero;
            }

            overlayFrameTime = -100f;
        }

        /// <summary>
        /// Hides Subnautica's health, food, water and oxygen bars, the frame around them and its
        /// tool bar, by shrinking them to nothing. (Switching them off instead doesn't stick: the
        /// game switches them back on.) Checked once a second, so parts the game creates later
        /// are caught too.
        /// </summary>
        private void HideBars()
        {
            foreach (string name in ReplacedBars)
            {
                try
                {
                    Type type = typeof(Player).Assembly.GetType(name);

                    if (type == null)
                    {
                        if (warnedMissing.Add(name))
                        {
                            Logger.LogWarning("Could not find " + name + " in this version of Subnautica, so that bar stays visible");
                        }

                        continue;
                    }

                    foreach (UnityEngine.Object found in Resources.FindObjectsOfTypeAll(type))
                    {
                        Component bar = found as Component;

                        if (bar == null)
                        {
                            continue;
                        }

                        Transform toHide = bar.transform;

                        // The Seamoth's and PRAWN suit's read-outs are run by a part that sits on
                        // the whole HUD; only the panel it shows ("root") is to go, not the HUD
                        // (which also holds the prompts for what is being looked at and held).
                        if (name == "uGUI_SeamothHUD" || name == "uGUI_ExosuitHUD")
                        {
                            FieldInfo panelField = type.GetField("root", BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic);
                            GameObject panel = panelField != null ? panelField.GetValue(bar) as GameObject : null;

                            if (panel == null || panel.transform == bar.transform || panel.GetComponentInChildren<HandReticle>(true) != null)
                            {
                                continue;
                            }

                            toHide = panel.transform;
                        }

                        Shrink(toHide);

                        if (name == "uGUI_QuickSlots" && bar.gameObject.scene.IsValid())
                        {
                            moduleBar = bar.transform;
                        }

                        if (warnedMissing.Add("path of " + name))
                        {
                            Logger.LogInfo("Hid " + name + " at " + PathOf(toHide));
                        }

                        // The four bars sit together inside a frame called "BarsPanel". Hide
                        // that too.
                        Transform frame = bar.transform.parent;

                        if (frame != null && frame.name == "BarsPanel")
                        {
                            Shrink(frame);
                        }
                    }
                }
                catch (Exception e)
                {
                    if (warnedMissing.Add(name))
                    {
                        Logger.LogWarning("Could not hide " + name + ": " + e.Message);
                    }
                }
            }
        }

        /// <summary>
        /// Subnautica's tool bar is kept hidden (Minecraft's hotbar stands in for it), but in a
        /// vehicle the same bar shows the vehicle's modules, which Minecraft has nothing for. So
        /// while in a vehicle it is shown again, moved to the left: centred a quarter of the way
        /// across the screen, clear of Minecraft's hotbar in the middle. Done every frame, after
        /// the once-a-second hiding.
        /// </summary>
        private void TendModuleBar()
        {
            if (moduleBar == null)
            {
                moduleBarMoved = false;
                return;
            }

            try
            {
                bool wanted = OverlayShowing() && !pdaOpen && ridingNow && riddenVehicle != null;
                Vector3 full;

                if (wanted && hiddenBars.TryGetValue(moduleBar, out full))
                {
                    if (!moduleBarMoved)
                    {
                        moduleBarMoved = true;
                        moduleBarHome = moduleBar.localPosition;
                    }

                    // A quarter of the screen's width, in the bar's own units: measured on the
                    // topmost of the layout boxes it sits inside.
                    RectTransform area = moduleBar as RectTransform;

                    while (area != null && area.parent is RectTransform)
                    {
                        area = (RectTransform)area.parent;
                    }

                    Transform holder = moduleBar.parent;
                    float scale = area != null && holder != null && holder.lossyScale.x > 0.0001f ? area.lossyScale.x / holder.lossyScale.x : 1f;
                    float quarter = (area != null ? area.rect.width : 1920f) * 0.25f * scale;

                    moduleBar.localScale = full;
                    moduleBar.localPosition = new Vector3(moduleBarHome.x - quarter, moduleBarHome.y, moduleBarHome.z);
                }
                else if (moduleBarMoved)
                {
                    moduleBarMoved = false;
                    moduleBar.localPosition = moduleBarHome;

                    // Still linked: back to hidden. (Unlinked, everything has been put back already.)
                    if (hiddenBars.ContainsKey(moduleBar))
                    {
                        moduleBar.localScale = Vector3.zero;
                    }
                }
            }
            catch (Exception e)
            {
                if (warnedMissing.Add("module bar"))
                {
                    Logger.LogWarning("Could not show the vehicle's module bar: " + e.Message);
                }
            }
        }

        /// <summary>
        /// Runs before Subnautica readies its water for a camera. The water is laid out from
        /// where the camera is and which way it faces, so the camera has to be in its
        /// outside-view place by then: otherwise, looking back from the front, the sea's
        /// surface was laid out behind the camera, where the eyes had been looking.
        /// </summary>
        /// <summary>True while the camera is outside the Cyclops looking in (the front view from its helm).</summary>
        private static bool outsideHullNow;
        private bool movedForWater;
        private static MethodInfo viewPlanesOf;
        private static FieldInfo viewPlanesFrame;
        private static bool viewPlanesLookedUp;

        /// <summary>
        /// Subnautica works out once a frame what a camera can see and keeps the answer. After
        /// this, it works it out again the next time it is asked.
        /// </summary>
        private static void ForgetViewPlanes(Camera camera)
        {
            if (!viewPlanesLookedUp)
            {
                viewPlanesLookedUp = true;

                foreach (Assembly assembly in AppDomain.CurrentDomain.GetAssemblies())
                {
                    Type utils = assembly.GetType("CameraUtils", false);
                    Type data = assembly.GetType("CameraData", false);

                    if (utils != null && data != null)
                    {
                        viewPlanesOf = utils.GetMethod("EnsureData", BindingFlags.Static | BindingFlags.Public | BindingFlags.NonPublic);
                        viewPlanesFrame = data.GetField("frameUpdated", BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic);
                        break;
                    }
                }
            }

            if (viewPlanesOf != null && viewPlanesFrame != null && camera != null)
            {
                object kept = viewPlanesOf.Invoke(null, new object[] { camera });

                if (kept != null)
                {
                    viewPlanesFrame.SetValue(kept, -1);
                }
            }
        }

        /// <summary>
        /// Early in each frame Subnautica picks which pieces of the sea's surface to draw, going
        /// by what the camera can see at that moment: the view through the eyes. In an outside
        /// view the picture is drawn from somewhere else, and from the front it faces the other
        /// way entirely, so the pieces picked were the wrong ones and the surface went missing.
        /// For just that step, the camera is put where the picture will be drawn from.
        /// </summary>
        public static void BeforeWaterLaidOut()
        {
            try
            {
                if (self != null && self.outsideCamera != null && self.movedCamera == null)
                {
                    self.BeforeCameraDraws(self.outsideCamera);
                    self.movedForWater = self.movedCamera != null;

                    if (self.movedForWater)
                    {
                        ForgetViewPlanes(self.outsideCamera);
                    }
                }
            }
            catch (Exception)
            {
                // The surface is then picked from the view through the eyes, as before.
            }
        }

        /// <summary>And straight back again, so everything else this frame sees the camera at the eyes.</summary>
        public static void AfterWaterLaidOut()
        {
            try
            {
                if (self != null && self.movedForWater)
                {
                    self.movedForWater = false;
                    Camera moved = self.movedCamera;
                    self.RestoreCamera();
                    ForgetViewPlanes(moved);
                }
            }
            catch (Exception)
            {
                // The camera is put back at the next step that checks, a moment later.
            }
        }

        /// <summary>
        /// Aboard the Cyclops, Subnautica leaves the sea's haze out of the picture (it would fog
        /// up the cabin). It makes an exception for its free-flying debug camera, which may be
        /// outside; the front view from the helm is outside too, so it claims the same exception.
        /// </summary>
        public static void HazeOutsideHull(ref bool __result)
        {
            if (outsideHullNow)
            {
                __result = true;
            }
        }

        // ---- Not shoving the Cyclops about ------------------------------------------------------
        //
        // While linked, the player's body is carried about by this mod and nothing can push it.
        // To Unity's physics that makes it an unstoppable object: if it overlaps the Cyclops's
        // hull by a hair (a moment's lag, or Minecraft's player still being put in place after
        // loading), it is the 12-tonne sub that gets shoved aside, tilting and ramming into
        // things. So the body is told not to press on the Cyclops's solid parts at all. Its
        // hatches and other areas the game watches still notice the player, and the player
        // still can't walk through the hull: that is checked separately (see AnswerSweep).

        /// <summary>Every part of a hull the body has been told not to press on, kept until the link ends so each can be put back.</summary>
        private readonly HashSet<Collider> hullsEased = new HashSet<Collider>();
        private readonly List<Collider> hullScratch = new List<Collider>();
        private readonly List<Collider> ownScratch = new List<Collider>();
        private float nextHullCheck;

        private void TendHullCollisions(Player player)
        {
            if (Time.unscaledTime < nextHullCheck || player == null)
            {
                return;
            }

            nextHullCheck = Time.unscaledTime + 2f;

            try
            {
                player.GetComponentsInChildren(true, ownScratch);

                if (!linked)
                {
                    // Subnautica moves the player again: its body must stand on the deck as usual.
                    foreach (Collider part in hullsEased)
                    {
                        if (part == null || !part.enabled || !part.gameObject.activeInHierarchy)
                        {
                            continue;
                        }

                        foreach (Collider own in ownScratch)
                        {
                            if (own != null && !own.isTrigger && own.enabled && own.gameObject.activeInHierarchy)
                            {
                                Physics.IgnoreCollision(own, part, false);
                            }
                        }
                    }

                    hullsEased.Clear();
                    cyclopsHulls.Clear();
                    return;
                }

                // Parts that have been destroyed since are dropped from the record.
                hullsEased.RemoveWhere(gone => gone == null);

                // The solid parts of each Cyclops are also noted for keeping Minecraft's blocks off them (see TendBlocksNearSubs).
                // (A hull already on the list keeps its record of which blocks have been dealt
                // with, so those aren't gone over again every time; a hull whose parts have
                // changed, something having been built aboard, starts afresh.)
                hullsBefore.Clear();
                hullsBefore.AddRange(cyclopsHulls);
                cyclopsHulls.Clear();

                foreach (SubRoot sub in UnityEngine.Object.FindObjectsOfType<SubRoot>())
                {
                    if (sub == null || !sub.isCyclops || (sub.transform.position - player.transform.position).sqrMagnitude > 150f * 150f)
                    {
                        continue;
                    }

                    sub.GetComponentsInChildren(false, hullScratch);

                    SubHull hull = null;

                    foreach (SubHull before in hullsBefore)
                    {
                        if (before.sub == sub)
                        {
                            hull = before;
                        }
                    }

                    if (hull == null)
                    {
                        hull = new SubHull();
                        hull.sub = sub;
                    }

                    int partsBefore = hull.parts.Count;
                    hull.parts.Clear();
                    cyclopsHulls.Add(hull);

                    foreach (Collider part in hullScratch)
                    {
                        if (part != null && !part.isTrigger && part.enabled && !part.transform.IsChildOf(player.transform))
                        {
                            hull.parts.Add(part);
                        }
                    }

                    if (hull.parts.Count != partsBefore)
                    {
                        hull.eased.Clear();
                    }

                    foreach (Collider part in hullScratch)
                    {
                        if (part == null || part.isTrigger || !part.enabled || part.transform.IsChildOf(player.transform))
                        {
                            continue;
                        }

                        foreach (Collider own in ownScratch)
                        {
                            if (own != null && !own.isTrigger && own.enabled && own.gameObject.activeInHierarchy)
                            {
                                Physics.IgnoreCollision(own, part, true);
                            }
                        }

                        hullsEased.Add(part);
                    }
                }

            }
            catch (Exception e)
            {
                if (warnedMissing.Add("hull collisions"))
                {
                    Logger.LogWarning("Could not stop the player's body pressing on the Cyclops: " + e.Message);
                }
            }
        }

        private static PropertyInfo waitingProperty;
        private static bool waitingLookedUp;

        /// <summary>Whether Subnautica is showing its loading screen (the world, or part of it, isn't there yet).</summary>
        private static bool WorldLoading()
        {
            try
            {
                if (!waitingLookedUp)
                {
                    waitingLookedUp = true;
                    Type screen = typeof(Player).Assembly.GetType("WaitScreen");
                    waitingProperty = screen != null ? screen.GetProperty("IsWaiting", BindingFlags.Static | BindingFlags.Public | BindingFlags.NonPublic) : null;
                }

                return waitingProperty != null && (bool)waitingProperty.GetValue(null, null);
            }
            catch (Exception)
            {
                return false;
            }
        }

        public static void MoveCameraFirst(Component __instance)
        {
            try
            {
                if (self != null && self.outsideCamera != null && __instance != null && __instance.gameObject == self.outsideCamera.gameObject)
                {
                    self.BeforeCameraDraws(self.outsideCamera);
                }
            }
            catch (Exception)
            {
                // The view is then moved a moment later, as before.
            }
        }

        private void Shrink(Transform part)
        {
            if (part.localScale != Vector3.zero)
            {
                if (!hiddenBars.ContainsKey(part))
                {
                    hiddenBars[part] = part.localScale;
                }

                part.localScale = Vector3.zero;
            }
        }

        /// <summary>Where something sits in the game's tree of objects, for the log: "Grandparent/Parent/Name".</summary>
        private static string PathOf(Transform part)
        {
            string path = part.name;

            for (Transform up = part.parent; up != null; up = up.parent)
            {
                path = up.name + "/" + path;
            }

            return path;
        }

        private void ShowBars()
        {
            foreach (KeyValuePair<Transform, Vector3> entry in hiddenBars)
            {
                // A part may have been destroyed since (leaving to the main menu, say).
                if (entry.Key != null)
                {
                    entry.Key.localScale = entry.Value;
                }
            }

            hiddenBars.Clear();
        }

        /// <summary>Whether Subnautica's PDA is open, found by name (Player.GetPDA().isInUse).</summary>
        private bool PdaOpen(Player player)
        {
            try
            {
                if (!getPdaLookupDone)
                {
                    getPdaLookupDone = true;
                    getPda = typeof(Player).GetMethod("GetPDA", BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic, null, Type.EmptyTypes, null);

                    if (getPda == null)
                    {
                        Logger.LogWarning("Could not find Subnautica's PDA; Minecraft's hand will cover it");
                    }
                }

                if (getPda == null)
                {
                    return false;
                }

                UnityEngine.Object pda = getPda.Invoke(player, null) as UnityEngine.Object;
                return pda != null && ReadBool(pda, "isInUse");
            }
            catch (Exception)
            {
                return false;
            }
        }

        /// <summary>
        /// Stops Subnautica reacting to the number keys and scroll wheel, which now belong to
        /// Minecraft's hotbar. Without this it would take a tool out (with its sound) before this
        /// mod could put it away again.
        ///
        /// This uses Harmony, a library that comes with the mod loader. It slots a small check
        /// (AllowToolKey, below) in front of Subnautica's own tool-selecting methods. If the
        /// check says no, Subnautica's method doesn't run.
        /// </summary>
        private void BlockSubnauticaToolKeys()
        {
            string[] names = { "SlotKeyDown", "SlotKeyHeld", "SlotKeyUp", "SlotNext", "SlotPrevious" };

            try
            {
                Type quickSlots = typeof(Player).Assembly.GetType("QuickSlots");

                if (quickSlots == null)
                {
                    Logger.LogWarning("Could not find Subnautica's quick slots, so its tool keys are not blocked");
                    return;
                }

                Harmony harmony = new Harmony("com.example.minecraftlink");
                HarmonyMethod check = new HarmonyMethod(typeof(MinecraftLinkPlugin).GetMethod("AllowToolKey", BindingFlags.Static | BindingFlags.Public));
                int blocked = 0;

                foreach (MethodInfo method in quickSlots.GetMethods(BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.DeclaredOnly))
                {
                    if (Array.IndexOf(names, method.Name) >= 0)
                    {
                        harmony.Patch(method, check);
                        blocked++;
                    }
                }

                Logger.LogInfo("Blocking " + blocked + " of Subnautica's tool key methods while linked");

                // While typing into Minecraft's chat box, none of Subnautica's own controls
                // should react. Everything in the game asks GameInput which buttons are down
                // and which way the movement keys point, so those answers are replaced with
                // "nothing" for as long as typing lasts.
                HarmonyMethod noButton = new HarmonyMethod(typeof(MinecraftLinkPlugin).GetMethod("NoButtonWhileTyping", BindingFlags.Static | BindingFlags.Public));
                HarmonyMethod noMovement = new HarmonyMethod(typeof(MinecraftLinkPlugin).GetMethod("NoMovementWhileTyping", BindingFlags.Static | BindingFlags.Public));
                HarmonyMethod noLooking = new HarmonyMethod(typeof(MinecraftLinkPlugin).GetMethod("NoLookingWhileTyping", BindingFlags.Static | BindingFlags.Public));
                string[] buttonChecks = { "GetButtonDown", "GetButtonHeld", "GetButtonUp" };
                int silenced = 0;

                foreach (MethodInfo method in typeof(GameInput).GetMethods(BindingFlags.Static | BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.DeclaredOnly))
                {
                    if (method.ReturnType == typeof(bool) && Array.IndexOf(buttonChecks, method.Name) >= 0)
                    {
                        harmony.Patch(method, noButton);
                        silenced++;
                    }
                    else if (method.ReturnType == typeof(Vector3) && method.Name == "GetMoveDirection")
                    {
                        harmony.Patch(method, noMovement);
                        silenced++;
                    }
                    else if (method.ReturnType == typeof(Vector2) && method.Name == "GetLookDelta")
                    {
                        // Mouse movement for looking around. With a Minecraft screen open the
                        // mouse moves the pointer instead, so the view should hold still.
                        harmony.Patch(method, noLooking);
                        silenced++;
                    }
                }

                Logger.LogInfo("Silencing " + silenced + " of Subnautica's control checks while typing");

                // The outside views move the camera just before it draws. Subnautica's water is
                // readied at the same moment, and has to come second (see MoveCameraFirst).
                HarmonyMethod cameraFirst = new HarmonyMethod(typeof(MinecraftLinkPlugin).GetMethod("MoveCameraFirst", BindingFlags.Static | BindingFlags.Public));
                int readied = 0;

                foreach (string waterName in new[] { "WaterSurfaceOnCamera", "WaterscapeVolumeOnCamera", "ShaderGlobals" })
                {
                    try
                    {
                        Type water = typeof(Player).Assembly.GetType(waterName);
                        MethodInfo readying = water != null ? water.GetMethod("OnPreCull", BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic) : null;

                        if (readying != null)
                        {
                            harmony.Patch(readying, cameraFirst);
                            readied++;
                        }
                    }
                    catch (Exception e)
                    {
                        Logger.LogWarning("Could not put the outside view ahead of " + waterName + ": " + e.Message);
                    }
                }

                Logger.LogInfo("Moving the outside view's camera ahead of " + readied + " of Subnautica's water steps");

                // The sea's surface is picked out earlier still, and aboard the Cyclops its haze is left out (see both methods).
                try
                {
                    Type surface = typeof(Player).Assembly.GetType("WaterSurfaceOnCamera");
                    MethodInfo laidOut = surface != null ? surface.GetMethod("OnUpdate", BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic) : null;

                    if (laidOut != null)
                    {
                        harmony.Patch(laidOut,
                            new HarmonyMethod(typeof(MinecraftLinkPlugin).GetMethod("BeforeWaterLaidOut", BindingFlags.Static | BindingFlags.Public)),
                            new HarmonyMethod(typeof(MinecraftLinkPlugin).GetMethod("AfterWaterLaidOut", BindingFlags.Static | BindingFlags.Public)));
                        Logger.LogInfo("Picking the sea's surface from the outside view's camera");
                    }
                    else
                    {
                        Logger.LogWarning("Could not find where Subnautica picks the sea's surface, so it may go missing in the front view");
                    }

                    Type freecam = typeof(Player).Assembly.GetType("FreecamController");
                    MethodInfo flying = freecam != null ? freecam.GetMethod("GetActive", BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic) : null;

                    if (flying != null)
                    {
                        harmony.Patch(flying, null, new HarmonyMethod(typeof(MinecraftLinkPlugin).GetMethod("HazeOutsideHull", BindingFlags.Static | BindingFlags.Public)));
                    }
                }
                catch (Exception e)
                {
                    Logger.LogWarning("Could not adjust Subnautica's water for the outside views: " + e.Message);
                }

                // In a vehicle, getting out is on Alt (E opens Minecraft's inventory), so the prompt
                // Subnautica shows for it is reworded to say so.
                try
                {
                    MethodInfo prompt = typeof(LanguageCache).GetMethod("GetButtonFormat", BindingFlags.Static | BindingFlags.Public | BindingFlags.NonPublic,
                        null, new[] { typeof(string), typeof(GameInput.Button) }, null);

                    if (prompt != null)
                    {
                        harmony.Patch(prompt, null, new HarmonyMethod(typeof(MinecraftLinkPlugin).GetMethod("ExitPrompt", BindingFlags.Static | BindingFlags.Public)));
                        Logger.LogInfo("Rewording Subnautica's \"exit\" prompt for vehicles (Alt)");
                    }
                    else
                    {
                        Logger.LogWarning("Could not find Subnautica's button prompts, so the vehicle prompt still names E (the key is Alt)");
                    }
                }
                catch (Exception e)
                {
                    Logger.LogWarning("Could not reword the vehicle exit prompt: " + e.Message);
                }

                // Subnautica has fall damage of its own. Its walking code still runs on the
                // character this mod carries around, and it kept deciding the player had fallen
                // and landed. Minecraft is in charge of falls now, so that damage is turned away.
                damageLog = Logger;
                HarmonyMethod damageCheck = new HarmonyMethod(typeof(MinecraftLinkPlugin).GetMethod("AllowPlayerDamage", BindingFlags.Static | BindingFlags.Public));
                int guarded = 0;

                foreach (MethodInfo method in typeof(LiveMixin).GetMethods(BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.DeclaredOnly))
                {
                    if (method.Name == "TakeDamage")
                    {
                        harmony.Patch(method, damageCheck);
                        guarded++;
                    }
                }

                Logger.LogInfo("Checking " + guarded + " of Subnautica's damage methods for its own fall damage");

                // And the same damage methods are watched for creatures dying to the player (see NoteKill).
                HarmonyMethod before = new HarmonyMethod(typeof(MinecraftLinkPlugin).GetMethod("NoteHealthBefore", BindingFlags.Static | BindingFlags.Public));
                HarmonyMethod after = new HarmonyMethod(typeof(MinecraftLinkPlugin).GetMethod("NoteKill", BindingFlags.Static | BindingFlags.Public));

                foreach (MethodInfo method in typeof(LiveMixin).GetMethods(BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.DeclaredOnly))
                {
                    if (method.Name == "TakeDamage")
                    {
                        harmony.Patch(method, before, after);
                    }
                }

                // Subnautica's own movement of the player: walking, jumping, gravity, swimming.
                // Minecraft does all of that now, so while linked these methods are skipped
                // outright. "UpdateMove" is where each kind of movement works out and applies
                // one step; the others are the walking code's per-frame work (gravity, jumping,
                // sliding, being carried by platforms).
                HarmonyMethod skip = new HarmonyMethod(typeof(MinecraftLinkPlugin).GetMethod("SkipWhileLinked", BindingFlags.Static | BindingFlags.Public));
                HarmonyMethod skipMove = new HarmonyMethod(typeof(MinecraftLinkPlugin).GetMethod("SkipMoveWhileLinked", BindingFlags.Static | BindingFlags.Public));
                string[] motorTypes = { "GroundMotor", "UnderwaterMotor", "PlayerMotor" };
                string[] motorMethods = { "UpdateMove", "UpdateFunction", "FixedUpdate", "ApplyGravityAndJumping", "ApplyInputVelocityChange", "MoveWithPlatform" };
                int stopped = 0;

                foreach (string typeName in motorTypes)
                {
                    Type motor = typeof(Player).Assembly.GetType(typeName);

                    if (motor == null)
                    {
                        continue;
                    }

                    foreach (MethodInfo method in motor.GetMethods(BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.DeclaredOnly))
                    {
                        // Only methods with a body of their own, of the two shapes handled below.
                        if (Array.IndexOf(motorMethods, method.Name) < 0 || method.IsAbstract)
                        {
                            continue;
                        }

                        if (method.ReturnType == typeof(void))
                        {
                            harmony.Patch(method, skip);
                            stopped++;
                        }
                        else if (method.ReturnType == typeof(Vector3))
                        {
                            harmony.Patch(method, skipMove);
                            stopped++;
                        }
                    }
                }

                if (stopped == 0)
                {
                    Logger.LogWarning("Could not find Subnautica's player movement code, so it keeps running underneath Minecraft's");
                }
                else
                {
                    Logger.LogInfo("Switching off " + stopped + " of Subnautica's player movement methods while linked");
                }

                // Subnautica's footstep sounds. Its walking code plays them as its character
                // "walks", which no longer matches what Minecraft's player is doing.
                FieldInfo footsteps = typeof(Player).GetField("footStepSounds", BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic);
                Type footstepType = footsteps != null ? footsteps.FieldType : typeof(Player).Assembly.GetType("FootstepSounds");
                HarmonyMethod quiet = new HarmonyMethod(typeof(MinecraftLinkPlugin).GetMethod("AllowFootstep", BindingFlags.Static | BindingFlags.Public));
                int hushed = 0;

                if (footstepType != null)
                {
                    foreach (MethodInfo method in footstepType.GetMethods(BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.DeclaredOnly))
                    {
                        if (method.Name == "OnStep")
                        {
                            harmony.Patch(method, quiet);
                            hushed++;
                        }
                    }
                }

                if (hushed == 0)
                {
                    Logger.LogWarning("Could not find Subnautica's footstep sounds, so they stay on");
                }
                else
                {
                    Logger.LogInfo("Silencing Subnautica's footsteps while linked");
                }

                // Subnautica closes the Fabricator's menu when you click away from it. A click on
                // this mod's panel of Minecraft materials must not count as that.
                HarmonyMethod keepMenu = new HarmonyMethod(typeof(MinecraftLinkPlugin).GetMethod("KeepFabricatorMenu", BindingFlags.Static | BindingFlags.Public));
                int kept = 0;

                foreach (string[] target in new[] { new[] { "uGUI_InputGroup", "Deselect" }, new[] { "FPSInputModule", "DeselectGroup" }, new[] { "FPSInputModule", "ChangeGroup" } })
                {
                    Type owner = typeof(Player).Assembly.GetType(target[0]);

                    if (owner == null)
                    {
                        continue;
                    }

                    foreach (MethodInfo method in owner.GetMethods(BindingFlags.Static | BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.DeclaredOnly))
                    {
                        if (method.Name == target[1])
                        {
                            harmony.Patch(method, keepMenu);
                            kept++;
                        }
                    }
                }

                Logger.LogInfo("Keeping the Fabricator's menu open through clicks on the Minecraft panel (" + kept + " checks)");

                // And its game menu when you click beside it (see KeepGameMenu).
                HarmonyMethod keepGameMenu = new HarmonyMethod(typeof(MinecraftLinkPlugin).GetMethod("KeepGameMenu", BindingFlags.Static | BindingFlags.Public));
                Type inputModule = typeof(Player).Assembly.GetType("FPSInputModule");
                int keptMenu = 0;

                if (inputModule != null)
                {
                    foreach (MethodInfo method in inputModule.GetMethods(BindingFlags.Static | BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.DeclaredOnly))
                    {
                        if (method.Name == "ChangeGroup")
                        {
                            harmony.Patch(method, keepGameMenu);
                            keptMenu++;
                        }
                    }
                }

                if (keptMenu == 0)
                {
                    Logger.LogWarning("Could not find how Subnautica closes a menu on a click beside it, so its game menu still closes that way");
                }
            }
            catch (Exception e)
            {
                Logger.LogWarning("Could not block Subnautica's tool keys: " + e.Message);
            }
        }

        /// <summary>While typing: answer "that button is not pressed" and skip Subnautica's own check.</summary>
        public static bool NoButtonWhileTyping(GameInput.Button __0, MethodBase __originalMethod, ref bool __result)
        {
            if (typing || screenOpen)
            {
                __result = false;
                return false;
            }

            // In a vehicle (see "Vehicles"): getting out is a tap of Alt, not E; the scroll wheel
            // is for Minecraft's hotbar; and the right button eats if Minecraft holds food.
            if (ridingNow && !ownButtonCheck)
            {
                int button = (int)__0;

                if (button == ExitButton && button >= 0)
                {
                    __result = __originalMethod.Name == "GetButtonDown" && AltTapped();
                    return false;
                }

                if (button >= 0 && (button == CycleNextButton || button == CyclePrevButton || (button == RightHandButton && foodInHand)))
                {
                    __result = false;
                    return false;
                }
            }

            return true;
        }

        /// <summary>With a Minecraft screen open: answer "the mouse has not moved" and skip Subnautica's own check.</summary>
        public static bool NoLookingWhileTyping(ref Vector2 __result)
        {
            if (!screenOpen)
            {
                return true;
            }

            __result = Vector2.zero;
            return false;
        }

        /// <summary>While typing: answer "no movement keys are held" and skip Subnautica's own check.</summary>
        public static bool NoMovementWhileTyping(ref Vector3 __result)
        {
            if (!typing && !screenOpen)
            {
                return true;
            }

            __result = Vector3.zero;
            return false;
        }

        /// <summary>
        /// Runs before any damage is dealt to anything in Subnautica. For damage to the player
        /// while linked, impact damage (kind "Collide") and anything that comes from Subnautica's
        /// own falling and landing code is turned away. Everything else (bites, heat, drowning, damage sent by Minecraft)
        /// goes through. The first few hundred are noted in the log, to help with troubleshooting.
        /// </summary>
        public static bool AllowPlayerDamage(LiveMixin __instance, object[] __args)
        {
            if (!linkedNow || applyingMinecraftDamage || Player.main == null || __instance != Player.main.liveMixin)
            {
                return true;
            }

            float amount = 0f;
            string kind = "?";

            foreach (object arg in __args)
            {
                if (arg is float)
                {
                    amount = (float)arg;
                }
                else if (arg is DamageType)
                {
                    kind = arg.ToString();
                }
            }

            // Who asked for this damage? Walk back through the chain of methods that led here
            // and take the first one outside the damage code itself.
            string source = "?";
            bool fromFalling = false;

            try
            {
                System.Diagnostics.StackTrace trace = new System.Diagnostics.StackTrace(1, false);

                for (int i = 0; i < trace.FrameCount && i < 12; i++)
                {
                    MethodBase method = trace.GetFrame(i).GetMethod();

                    if (method == null || method.DeclaringType == null)
                    {
                        continue;
                    }

                    string owner = method.DeclaringType.Name;
                    string name = method.Name;

                    if (owner == "LiveMixin" || owner == "MinecraftLinkPlugin" || name.Contains("TakeDamage"))
                    {
                        continue;
                    }

                    if (source == "?")
                    {
                        source = owner + "." + name;
                    }

                    // Subnautica's walking code deciding the player has fallen or landed.
                    if (name.Contains("Land") || name.Contains("Fall") || owner.Contains("GroundMotor"))
                    {
                        fromFalling = true;
                    }
                }
            }
            catch (Exception)
            {
                // Not being able to tell just means going by the kind of damage alone.
            }

            bool turnedAway = fromFalling || kind == "Collide";

            if (damageNotes < 300 && damageLog != null)
            {
                damageNotes++;
                damageLog.LogInfo("Subnautica dealt the player " + amount.ToString("0.#", CultureInfo.InvariantCulture) + " damage of kind " + kind
                    + " from " + source + (turnedAway ? " (turned away)" : ""));
            }

            return !turnedAway;
        }

        /// <summary>While linked, skip one of Subnautica's player movement methods entirely.</summary>
        public static bool SkipWhileLinked()
        {
            return !linkedNow;
        }

        /// <summary>The same, for the methods that hand back how far the player moved: the answer is "not at all".</summary>
        public static bool SkipMoveWhileLinked(ref Vector3 __result)
        {
            if (!linkedNow)
            {
                return true;
            }

            __result = Vector3.zero;
            return false;
        }

        /// <summary>While linked, skip Subnautica's footstep sound.</summary>
        public static bool AllowFootstep()
        {
            return !linkedNow;
        }

        /// <summary>Returning false tells Harmony to skip Subnautica's own method.</summary>
        public static bool AllowToolKey()
        {
            return !blockSubnauticaTools;
        }

        // ---- Water ----------------------------------------------------------------------------

        /// <summary>
        /// Reads a yes/no value from one of the game's objects (usually the player) by name, whether the game keeps it as a
        /// method, a property or a plain field. Looked up by name so that a game update that
        /// renames it gives a warning in the log instead of stopping the mod from loading.
        /// </summary>
        private bool ReadBool(object player, string name)
        {
            const BindingFlags Any = BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic;

            try
            {
                Type type = player.GetType();

                MethodInfo method = type.GetMethod(name, Any, null, Type.EmptyTypes, null);
                if (method != null && method.ReturnType == typeof(bool))
                {
                    return (bool)method.Invoke(player, null);
                }

                PropertyInfo property = type.GetProperty(name, Any);
                if (property != null && property.PropertyType == typeof(bool))
                {
                    return (bool)property.GetValue(player, null);
                }

                FieldInfo field = type.GetField(name, Any);
                if (field != null && field.FieldType == typeof(bool))
                {
                    return (bool)field.GetValue(player);
                }

                if (warnedMissing.Add(name))
                {
                    Logger.LogWarning("Could not find " + type.Name + "." + name + " in this version of Subnautica");
                }
            }
            catch (Exception e)
            {
                if (warnedMissing.Add(name))
                {
                    Logger.LogWarning("Could not read " + name + ": " + e.Message);
                }
            }

            return false;
        }

        /// <summary>
        /// How fast the player swims in Subnautica right now, in metres per second, with the
        /// game's own adjustments for fins, air tanks and so on.
        /// </summary>
        private float ReadSwimSpeed(Player player)
        {
            const BindingFlags Any = BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic;

            try
            {
                if (!swimLookupDone)
                {
                    swimLookupDone = true;
                    swimMotorType = typeof(Player).Assembly.GetType("UnderwaterMotor");

                    if (swimMotorType != null)
                    {
                        swimSpeedField = swimMotorType.GetField("forwardMaxSpeed", Any);
                        swimSpeedAdjuster = swimMotorType.GetMethod("AlterMaxSpeed", Any, null, new[] { typeof(float) }, null);
                    }

                    if (swimMotorType == null || swimSpeedField == null)
                    {
                        Logger.LogWarning("Could not find Subnautica's swim speed; using " + DefaultSwimSpeed);
                    }
                }

                if (swimMotorType == null || swimSpeedField == null)
                {
                    return DefaultSwimSpeed;
                }

                Component motor = player.GetComponentInChildren(swimMotorType, true);

                if (motor == null)
                {
                    return DefaultSwimSpeed;
                }

                float speed = (float)swimSpeedField.GetValue(motor);

                if (swimSpeedAdjuster != null)
                {
                    speed = (float)swimSpeedAdjuster.Invoke(motor, new object[] { speed });
                }

                // Guard against a nonsense value.
                return Mathf.Clamp(speed, 2f, 15f);
            }
            catch (Exception e)
            {
                if (warnedMissing.Add("swim speed"))
                {
                    Logger.LogWarning("Could not read Subnautica's swim speed: " + e.Message);
                }

                return DefaultSwimSpeed;
            }
        }

        // ---- Collision ------------------------------------------------------------------------

        /// <summary>
        /// "SWEEP id cx cy cz hx hy hz dx dy dz step ground": Minecraft wants to move the player's
        /// box (centre c, half-size h) by d. Slide it through Subnautica's scenery and reply
        /// "SWEPT id rx ry rz ground walls [nx nz]..." with how far it actually got, whether it
        /// ended up standing on something, and which way any walls it slid along were facing.
        /// Positions are in Minecraft's coordinates, so z is flipped on the way in and out.
        /// </summary>
        private void AnswerSweep(string numbers, Player player)
        {
            string[] parts = numbers.Split(' ');
            float[] v = new float[12];

            if (parts.Length != 12)
            {
                return;
            }

            for (int i = 0; i < 12; i++)
            {
                if (!float.TryParse(parts[i], NumberStyles.Float, CultureInfo.InvariantCulture, out v[i]))
                {
                    return;
                }
            }

            Vector3 centre = new Vector3(v[1], v[2], -v[3]);
            Vector3 half = new Vector3(v[4], v[5], v[6]);
            Vector3 move = new Vector3(v[7], v[8], -v[9]);
            float stepHeight = v[10];
            bool wasOnGround = v[11] > 0.5f;

            Vector3 result = move;
            bool ground = false;
            float lift = 0f;
            walls.Clear();

            // The player's own movement: Minecraft checks its own blocks itself, exactly as it
            // does in any world, so the cubes that stand for them here are left out. (Dropped
            // items, whose questions have the big numbers, still land on them.)
            ignoreBlocksNow = v[0] < 1000000f;

            // With no world to ask about (loading, the menu, one of Subnautica's own animations)
            // the player is held still, below. Questions about anything else (a dropped item,
            // a mob, falling sand) are simply not answered until there is: Minecraft asks
            // again, and nothing is told it has landed on ground that isn't there.
            if (v[0] >= 1000000f && (player == null || WorldLoading() || player.cinematicModeActive))
            {
                ignoreBlocksNow = false;
                return;
            }

            // Aboard a moving Cyclops, the player's box is where Minecraft last knew the sub to
            // be: bring it up to where the sub is now, so its walls and floor are where the box expects.
            if (v[0] < 1000000f)
            {
                centre += CarryOwed();
            }

            if (player == null || WorldLoading())
            {
                // No world to stand in yet (Subnautica is loading, or back at its menu): hold
                // everything still, as if on solid ground, so nothing drops through scenery
                // that hasn't appeared.
                result = Vector3.zero;
                ground = true;
            }
            else if (player.cinematicModeActive)
            {
                // Subnautica is playing one of its own animations with the player (climbing into
                // the lifepod, say) and will put them somewhere new when it ends. Until then,
                // hold Minecraft's player still, as if standing on solid ground. Otherwise it
                // would carry on by itself, and could fall a long way before being moved.
                result = Vector3.zero;
                ground = true;
            }
            else if (player != null)
            {
                RefreshSolidLayers(player);

                // If the box has ended up inside something (the scenery moved, or the player
                // changed size), lift it clear first. Otherwise it would pass straight through,
                // because things the box is already inside can't stop it.
                lift = FindLift(centre, half, player, 1f);
                centre += Vector3.up * lift;

                if (lift > 0f)
                {
                    liftsDone++;
                }

                result = Slide(centre, half, move, player, ref ground, walls);

                bool wantsToGoSideways = move.x * move.x + move.z * move.z > 1e-8f;

                // Blocked by something low while on the ground: try stepping up onto it, the way
                // Minecraft steps up slabs and stairs. Go up, across, then back down, and keep
                // that if it got further.
                if (stepHeight > 0f && walls.Count > 0 && (wasOnGround || ground) && wantsToGoSideways)
                {
                    float up;
                    Vector3 unused;
                    CastBox(centre, half, Vector3.up, stepHeight, player, out up, out unused);

                    if (up > 0.01f)
                    {
                        Vector3 raised = centre + Vector3.up * up;
                        bool unusedGround = false;
                        stepWalls.Clear();
                        Vector3 across = Slide(raised, half, new Vector3(move.x, 0f, move.z), player, ref unusedGround, stepWalls);

                        float down;
                        Vector3 landing;
                        bool landed = CastBox(raised + across, half, Vector3.down, up + Mathf.Max(0f, -move.y), player, out down, out landing)
                            && landing.y >= FloorNormalY;
                        Vector3 stepped = Vector3.up * up + across + Vector3.down * down;

                        if (landed && stepped.x * stepped.x + stepped.z * stepped.z > result.x * result.x + result.z * result.z + 1e-6f)
                        {
                            result = stepped;
                            ground = true;
                            walls.Clear();
                            walls.AddRange(stepWalls);
                        }
                    }
                }

                // Walking downhill or off a small lip: keep the feet on the ground.
                if (wasOnGround && move.y <= 0f && !ground)
                {
                    float down;
                    Vector3 landing;

                    if (CastBox(centre + result, half, Vector3.down, SnapDownDistance, player, out down, out landing) && landing.y >= FloorNormalY)
                    {
                        result += Vector3.down * down;
                        ground = true;
                    }
                }
            }

            // The lift is part of how far the box moved.
            result += Vector3.up * lift;

            // For troubleshooting: note any time a player who was standing on something is
            // lifted, or ends up more than 3 cm lower without having asked to go down that far.
            if (sweepNotes < 300 && v[0] < 1000000f && player != null && (lift > 0f || (wasOnGround && result.y < -0.03f && result.y < move.y + 0.001f && move.y > -0.2f)))
            {
                sweepNotes++;
                Logger.LogInfo(string.Format(CultureInfo.InvariantCulture,
                    "Sweep note: at ({0:0.###}, {1:0.###}, {2:0.###}) asked ({3:0.###}, {4:0.###}, {5:0.###}) got ({6:0.###}, {7:0.###}, {8:0.###}) lift {9:0.###} ground {10} walls {11}",
                    centre.x, centre.y - half.y, centre.z, move.x, move.y, move.z, result.x, result.y, result.z, lift, ground, walls.Count));
            }
            sweepsAnswered++;
            ignoreBlocksNow = false;

            reply.Length = 0;
            reply.Append("SWEPT ").Append(((int)v[0]).ToString(CultureInfo.InvariantCulture))
                .Append(' ').Append(result.x.ToString("0.######", CultureInfo.InvariantCulture))
                .Append(' ').Append(result.y.ToString("0.######", CultureInfo.InvariantCulture))
                .Append(' ').Append((-result.z).ToString("0.######", CultureInfo.InvariantCulture))
                .Append(ground ? " 1 " : " 0 ");

            int wallCount = Mathf.Min(walls.Count, 2);
            reply.Append(wallCount.ToString(CultureInfo.InvariantCulture));

            for (int i = 0; i < wallCount; i++)
            {
                reply.Append(' ').Append(walls[i].x.ToString("0.####", CultureInfo.InvariantCulture))
                    .Append(' ').Append((-walls[i].z).ToString("0.####", CultureInfo.InvariantCulture));
            }

            Send(reply.ToString());
        }

        /// <summary>
        /// Moves a box through the scenery, sliding along whatever it hits, and returns how far
        /// it got. On a floor (anything up to about 45 degrees) it keeps its sideways speed and
        /// follows the slope up or down, as Minecraft does on stairs. On anything steeper it
        /// loses the part of its movement heading into the surface and keeps the rest.
        /// </summary>
        private Vector3 Slide(Vector3 start, Vector3 half, Vector3 move, Player player, ref bool ground, List<Vector3> wallsHit)
        {
            // Kept as a distance from the start, not a position: positions far from the middle
            // of the map are less exact, and Minecraft compares these numbers closely.
            Vector3 moved = Vector3.zero;
            Vector3 remaining = move;
            int planeCount = 0;

            for (int pass = 0; pass < 5; pass++)
            {
                float length = remaining.magnitude;

                if (length < 0.0001f)
                {
                    break;
                }

                Vector3 direction = remaining / length;
                float travel;
                Vector3 normal;

                if (!CastBox(start + moved, half, direction, length, player, out travel, out normal))
                {
                    // Nothing in the way: go the whole distance.
                    moved += remaining;
                    break;
                }

                moved += direction * travel;
                Vector3 rest = direction * (length - Mathf.Max(travel, 0f));
                Vector3 next;

                if (normal.y >= FloorNormalY)
                {
                    // Floor: keep going sideways, at the height the slope dictates.
                    ground = true;
                    next = new Vector3(rest.x, -(normal.x * rest.x + normal.z * rest.z) / normal.y, rest.z);
                }
                else
                {
                    // Wall, steep slope or ceiling: drop the part of the movement heading into it.
                    next = rest - normal * Vector3.Dot(rest, normal);

                    if (normal.y > -FloorNormalY)
                    {
                        float flat = Mathf.Sqrt(normal.x * normal.x + normal.z * normal.z);

                        if (flat > 0.0001f)
                        {
                            wallsHit.Add(new Vector3(normal.x / flat, 0f, normal.z / flat));
                        }
                    }
                }

                // If that would push into a surface hit earlier in this move (the corner where a
                // wall meets the floor, say), move along the line where the two meet instead.
                for (int i = 0; i < planeCount; i++)
                {
                    if (Vector3.Dot(next, slidePlanes[i]) < -0.0001f)
                    {
                        Vector3 crease = Vector3.Cross(normal, slidePlanes[i]);

                        if (crease.sqrMagnitude < 0.000001f)
                        {
                            next = Vector3.zero;
                        }
                        else
                        {
                            crease.Normalize();
                            next = crease * Vector3.Dot(rest, crease);

                            // Boxed in by a third surface as well: stop.
                            for (int j = 0; j < planeCount; j++)
                            {
                                if (Vector3.Dot(next, slidePlanes[j]) < -0.0001f)
                                {
                                    next = Vector3.zero;
                                }
                            }
                        }

                        break;
                    }
                }

                if (planeCount < slidePlanes.Length)
                {
                    slidePlanes[planeCount++] = normal;
                }

                remaining = next;
            }

            return moved;
        }

        /// <summary>
        /// Pushes a box through the scenery in a straight line. Returns true if it hit something,
        /// with how far it got and which way the surface it hit was facing. The distance can be
        /// slightly negative, meaning "back off a little": the surface has moved into the box.
        /// </summary>
        private bool CastBox(Vector3 centre, Vector3 half, Vector3 direction, float length, Player player, out float travel, out Vector3 normal)
        {
            Vector3 tested = new Vector3(half.x - Skin, half.y - Skin, half.z - Skin);
            int count = Physics.BoxCastNonAlloc(centre, tested, direction, sweepHits, Quaternion.identity, length + Skin, solidLayers, QueryTriggerInteraction.Ignore);

            float nearest = float.MaxValue;
            normal = Vector3.zero;

            for (int i = 0; i < count; i++)
            {
                RaycastHit hit = sweepHits[i];

                // Unity reports things the box is already inside like this. They are skipped, so
                // that a player who somehow ends up inside something can walk out of it.
                if (hit.distance <= 0f && hit.point == Vector3.zero)
                {
                    continue;
                }

                if (hit.distance < nearest && IsSolid(hit.collider, player))
                {
                    nearest = hit.distance;
                    normal = hit.normal;
                }
            }

            if (nearest - Skin >= length)
            {
                travel = length;
                normal = Vector3.zero;
                return false;
            }

            // Stop a little short. Subnautica's measurements come out about a centimetre off
            // from Minecraft's, and this keeps the player from resting slightly inside a block.
            travel = nearest - Skin - RestGap;

            // A surface should face against the movement. If Unity says otherwise (it can on
            // sharp edges), treat it as a flat stop.
            if (Vector3.Dot(normal, direction) > -0.0001f)
            {
                normal = -direction;
            }

            return true;
        }

        /// <summary>
        /// If a box is inside something solid, returns how far up it has to go to be clear of it
        /// (checked in steps of 5 cm, up to the given limit). Returns 0 if the box is already
        /// clear, or if going up doesn't help.
        /// </summary>
        private float FindLift(Vector3 centre, Vector3 half, Player player, float limit)
        {
            if (!BoxIsBlocked(centre, half, player))
            {
                return 0f;
            }

            for (float lift = 0.05f; lift <= limit; lift += 0.05f)
            {
                if (!BoxIsBlocked(centre + Vector3.up * lift, half, player))
                {
                    return lift;
                }
            }

            return 0f;
        }

        /// <summary>Whether a box overlaps any scenery, by more than the thin margin CastBox allows for.</summary>
        private bool BoxIsBlocked(Vector3 centre, Vector3 half, Player player)
        {
            // A touch bigger than the box CastBox tests with, so anything that would be missed
            // there is caught here.
            float margin = Skin - 0.005f;
            Vector3 tested = new Vector3(half.x - margin, half.y - margin, half.z - margin);
            int count = Physics.OverlapBoxNonAlloc(centre, tested, overlapHits, Quaternion.identity, solidLayers, QueryTriggerInteraction.Ignore);

            for (int i = 0; i < count; i++)
            {
                if (IsScenery(overlapHits[i], player))
                {
                    return true;
                }
            }

            return false;
        }

        /// <summary>
        /// Asks Unity which layers the player collides with, so Minecraft is solid in exactly
        /// the places Subnautica is. Checked again every second in case the game changes it.
        /// </summary>
        private void RefreshSolidLayers(Player player)
        {
            if (Time.unscaledTime - solidLayersTime < 1f)
            {
                return;
            }

            solidLayersTime = Time.unscaledTime;
            int playerLayer = player.gameObject.layer;
            solidLayers = 0;

            for (int layer = 0; layer < 32; layer++)
            {
                if (!Physics.GetIgnoreLayerCollision(playerLayer, layer))
                {
                    solidLayers |= 1 << layer;
                }
            }

            // Things get created and destroyed all the time; don't let the memory of them grow forever.
            if (sceneryCache.Count > 5000)
            {
                sceneryCache.Clear();
            }
        }

        /// <summary>
        /// Whether a collider is fixed scenery: anything that isn't the player's own body, a
        /// creature, or a loose item that can be picked up. Used when lifting the player clear of
        /// something they are inside, which should only happen for scenery.
        /// </summary>
        private bool IsScenery(Collider collider, Player player)
        {
            if (ignoreBlocksNow && IsMinecraftBlock(collider))
            {
                return false;
            }

            return Classify(collider, player) == Scenery;
        }

        /// <summary>True only while answering the player's own movement check. See AnswerSweep.</summary>
        private bool ignoreBlocksNow;

        /// <summary>Whether a collider is one of the cubes this mod builds for Minecraft's blocks.</summary>
        private bool IsMinecraftBlock(Collider collider)
        {
            return blockRoot != null && collider != null && collider.transform.IsChildOf(blockRoot.transform);
        }

        /// <summary>
        /// Whether the player bumps into a collider when moving: scenery, and creatures too.
        /// Small catchable fish and loose items are left out so they don't block the way. A
        /// creature that swims into the player doesn't trap them: things the player is already
        /// inside never block movement.
        /// </summary>
        private bool IsSolid(Collider collider, Player player)
        {
            if (ignoreBlocksNow && IsMinecraftBlock(collider))
            {
                return false;
            }

            int kind = Classify(collider, player);
            return kind == Scenery || kind == CreatureKind;
        }

        private const int NotSolid = 0;
        private const int Scenery = 1;
        private const int CreatureKind = 2;

        private int Classify(Collider collider, Player player)
        {
            if (collider == null)
            {
                return NotSolid;
            }

            int id = collider.GetInstanceID();
            int kind;

            if (!sceneryCache.TryGetValue(id, out kind))
            {
                Type diver = DiverType();

                // The player's own body, loose items, and other players (Nitrox's divers): as in
                // Minecraft, players walk through each other.
                if (collider.transform.IsChildOf(player.transform) || collider.GetComponentInParent<Pickupable>() != null
                    || (diver != null && collider.GetComponentInParent(diver) != null))
                {
                    kind = NotSolid;
                }
                else if (collider.GetComponentInParent<Creature>() != null)
                {
                    kind = CreatureKind;
                }
                else
                {
                    kind = Scenery;
                }

                sceneryCache[id] = kind;
            }

            return kind;
        }

        // ---- Minecraft's blocks ---------------------------------------------------------------

        /// <summary>
        /// Tells Minecraft what solid scenery the camera is pointed at, if any is in reach:
        /// "AIM x y z nx ny nz terrain" (the point, which way the surface faces, and 1 if it is terrain), or "AIM -".
        /// Minecraft uses it to place a block against Subnautica's scenery.
        /// </summary>
        private void SendAim(Player player, Camera view)
        {
            if (view == null)
            {
                Send("AIM -");
                return;
            }

            RefreshSolidLayers(player);
            int count = Physics.RaycastNonAlloc(view.transform.position, view.transform.forward, sweepHits, BlockReach, solidLayers, QueryTriggerInteraction.Ignore);

            float nearest = float.MaxValue;
            RaycastHit best = default(RaycastHit);

            for (int i = 0; i < count; i++)
            {
                if (sweepHits[i].distance < nearest && IsScenery(sweepHits[i].collider, player))
                {
                    nearest = sweepHits[i].distance;
                    best = sweepHits[i];
                }
            }

            if (nearest == float.MaxValue)
            {
                Send("AIM -");
                return;
            }

            // The last number says whether it is terrain (the sea floor, rock), which Minecraft
            // lets the player dig at, rather than something built or placed.
            // (Minecraft's own blocks are on that layer too, so habitats can stand on them; they aren't for digging at.)
            bool terrain = best.collider != null && best.collider.gameObject.layer == LayerID.TerrainCollider && !IsMinecraftBlock(best.collider);

            Send(string.Format(CultureInfo.InvariantCulture, "AIM {0:0.###} {1:0.###} {2:0.###} {3:0.###} {4:0.###} {5:0.###} {6}",
                best.point.x, best.point.y, best.point.z, best.normal.x, best.normal.y, best.normal.z, terrain ? 1 : 0));
        }

        /// <summary>
        /// "x y z id colour box" adds or changes a block; "x y z air" removes it. The id says which
        /// model to draw (see HandleModel); the colour is used when there is no model; the box is
        /// the solid part as six numbers (low corner, high corner), or "-" for none.
        /// </summary>
        private void HandleBlock(string text)
        {
            string[] parts = text.Split(' ');
            int x, y, z;

            if (parts.Length < 4
                || !int.TryParse(parts[0], NumberStyles.Integer, CultureInfo.InvariantCulture, out x)
                || !int.TryParse(parts[1], NumberStyles.Integer, CultureInfo.InvariantCulture, out y)
                || !int.TryParse(parts[2], NumberStyles.Integer, CultureInfo.InvariantCulture, out z))
            {
                return;
            }

            // Pack the three numbers into one. Offsets keep each part positive.
            long key = ((long)(x + 0x100000) << 42) | ((long)(y + 0x100000) << 21) | (long)(z + 0x100000);
            GameObject existing;

            // Simplest way to change a block is to remove the old one and build the new one.
            if (blocks.TryGetValue(key, out existing))
            {
                if (existing != null)
                {
                    // Destroy only takes effect at the end of the frame. Switching the cube off
                    // first makes it stop being solid at once, so the item a broken block
                    // drops doesn't bump into the block it came from.
                    existing.SetActive(false);
                    Destroy(existing);
                }

                blocks.Remove(key);

                HideCracksAt(key);

                // (Only something solid going matters to a habitat's legs: flowing water and
                // burning fire change all the time.)
                if (existing != null && existing.GetComponent<BoxCollider>() != null)
                {
                    LegsNeedSettling();
                }
            }

            int id, colour;

            if (parts[3] == "air" || parts.Length < 6
                || !int.TryParse(parts[3], NumberStyles.Integer, CultureInfo.InvariantCulture, out id)
                || !int.TryParse(parts[4], NumberStyles.HexNumber, CultureInfo.InvariantCulture, out colour))
            {
                return;
            }

            try
            {
                if (blockRoot == null)
                {
                    blockRoot = new GameObject("MinecraftBlocks");
                }

                GameObject block = new GameObject("MinecraftBlock");
                block.transform.SetParent(blockRoot.transform, false);

                // The object sits at the block's low corner. Subnautica's z axis runs the other
                // way, so everything inside the block is built with z flipped (see BuildMesh).
                block.transform.position = new Vector3(x, y, -z);

                MeshFilter shape = block.AddComponent<MeshFilter>();
                MeshRenderer renderer = block.AddComponent<MeshRenderer>();
                BlockModel model;

                if (blockModels.TryGetValue(id, out model) && model.mesh != null)
                {
                    // One material per differently-tinted part of the model.
                    Material[] materials = new Material[model.tints.Length];

                    // A block that gives off light is drawn glowing.
                    bool glows = blockLights.ContainsKey(id);

                    for (int i = 0; i < materials.Length; i++)
                    {
                        materials[i] = glows ? GlowMaterial(model.tints[i]) : model.seeThrough ? WaterMaterial(model.tints[i]) : TexturedMaterial(model.tints[i]);
                    }

                    shape.sharedMesh = model.mesh;
                    renderer.sharedMaterials = materials;
                }
                else
                {
                    if (plainCube == null)
                    {
                        plainCube = BuildMesh(new List<float[]> { CubeFaces }, new List<float[]> { new float[48] });
                    }

                    shape.sharedMesh = plainCube;
                    renderer.sharedMaterial = BlockMaterial(colour & 0xFFFFFF, null);

                    // A colour beyond six digits: Minecraft draws this block by special code
                    // (a chest, a bed) and sends what it looks like separately, the way it sends
                    // creatures. Only the solid part is wanted here, not a cube to look at.
                    if (colour > 0xFFFFFF)
                    {
                        renderer.enabled = false;
                    }
                }

                // The solid part.
                float[] box = new float[6];

                if (parts.Length == 11)
                {
                    bool ok = true;

                    for (int i = 0; i < 6; i++)
                    {
                        ok &= float.TryParse(parts[5 + i], NumberStyles.Float, CultureInfo.InvariantCulture, out box[i]);
                    }

                    if (ok)
                    {
                        // A solid block counts as terrain to Subnautica (it goes by an object's
                        // "layer"): habitats can be built on it and against it as they can on
                        // the sea floor, and creatures steer round it.
                        block.layer = LayerID.TerrainCollider;
                        BoxCollider solid = block.AddComponent<BoxCollider>();
                        solid.size = new Vector3(box[3] - box[0], box[4] - box[1], box[5] - box[2]);
                        solid.center = new Vector3((box[0] + box[3]) / 2f, (box[1] + box[4]) / 2f, -(box[2] + box[5]) / 2f);
                        LegsNeedSettling();
                        EaseNewBlock(solid);
                    }
                }

                blocks[key] = block;
                ApplySubnauticaLighting(block);
                AddLight(block, id);
            }
            catch (Exception e)
            {
                if (warnedMissing.Add("block"))
                {
                    Logger.LogWarning("Could not build a Minecraft block: " + e);
                }
            }
        }

        // ---- Habitat legs on Minecraft's blocks ---------------------------------------------------
        //
        // A foundation's legs reach down to whatever is under it. Subnautica works that out when
        // it builds the habitat, which on loading a save is before Minecraft has sent its blocks:
        // a habitat standing on blocks got legs that ran straight through them to the sea bed.
        // So a moment after solid blocks appear or go, every foundation measures its legs again.

        private float settleLegsAt;
        private bool warnedLegs;

        private void LegsNeedSettling()
        {
            if (settleLegsAt <= 0f)
            {
                settleLegsAt = Time.unscaledTime + 1f;
            }
        }

        /// <summary>Every frame: if blocks have changed and the moment has passed, have each foundation measure its legs again.</summary>
        private void SettleHabitatLegs()
        {
            if (settleLegsAt <= 0f || Time.unscaledTime < settleLegsAt || WorldLoading())
            {
                return;
            }

            settleLegsAt = 0f;

            try
            {
                foreach (BaseFoundationPiece foundation in FindObjectsOfType<BaseFoundationPiece>())
                {
                    Base habitat = foundation.GetComponentInParent<Base>();

                    if (habitat != null)
                    {
                        // Subnautica's own measuring, which looks down for anything solid: now it finds the blocks.
                        ((IBaseAccessoryGeometry)foundation).BuildGeometry(habitat, false);
                    }
                }
            }
            catch (Exception e)
            {
                if (!warnedLegs)
                {
                    warnedLegs = true;
                    Logger.LogWarning("Could not settle a habitat's legs on Minecraft's blocks: " + e);
                }
            }
        }

        /// <summary>
        /// "id quads": what one kind of block looks like. Quads (flat four-cornered faces) are
        /// separated by ";". Each is a tint (six hex digits) then four corners of "x,y,z,u,v":
        /// a position inside the block, and a position on the block atlas. A "~" in front means
        /// the block's picture moves (fire).
        /// </summary>
        private void HandleModel(string text)
        {
            int space = text.IndexOf(' ');
            int id;

            if (!int.TryParse(space < 0 ? text : text.Substring(0, space), NumberStyles.Integer, CultureInfo.InvariantCulture, out id))
            {
                return;
            }

            BlockModel model = new BlockModel();
            blockModels[id] = model;
            string quads = space < 0 ? "" : text.Substring(space + 1).Trim();

            // Marks in front: "~" its picture moves and has gaps that move with it (fire), so
            // the shape has to be cut out afresh as it does; "%" it is see-through (water).
            while (quads.Length > 0 && (quads[0] == '~' || quads[0] == '%'))
            {
                if (quads[0] == '~')
                {
                    model.animated = true;
                }
                else
                {
                    model.seeThrough = true;
                }

                quads = quads.Substring(1);
            }

            model.quads = quads;

            // With no quads there is no model: the block will be drawn as a plain coloured cube.
            if (quads.Length > 0)
            {
                CutModel(quads, 0f, ref model.mesh, ref model.tints, ref model.cutOut, "block model");
            }
        }

        /// <summary>
        /// Builds a shape from Minecraft's description of a model's faces.
        ///
        /// Each face is cut up along the pixels of its picture, and the pieces over see-through
        /// pixels are left out. That is what gives leaves their gaps, glass its clear middle, a
        /// flame its outline and a dropped stick the shape of a stick, without relying on the
        /// material to hide see-through pixels (which Subnautica's would not do). Faces with no
        /// see-through pixels, which is most of them, are kept whole.
        ///
        /// "shift" is taken off every position: 0.5 puts the middle of the model at the centre,
        /// for things that turn. If a mesh is passed in, it is refilled rather than replaced, so
        /// everything already drawn with it changes too.
        /// </summary>
        private void CutModel(string quads, float shift, ref Mesh mesh, ref int[] tints, ref bool cutOut, string what)
        {
            try
            {
                // Quads are grouped by tint, because each tint needs its own material.
                List<int> tintList = new List<int>();
                List<List<float>> corners = new List<List<float>>();
                List<List<float>> textureSpots = new List<List<float>>();
                bool canCut = atlasPixels != null;
                int pieces = 0;
                Vector3[] p = new Vector3[4];
                Vector2[] t = new Vector2[4];

                foreach (string quad in quads.Split(';'))
                {
                    string[] numbers = quad.Split(',');

                    if (numbers.Length != 21)
                    {
                        continue;
                    }

                    int tint = int.Parse(numbers[0], NumberStyles.HexNumber, CultureInfo.InvariantCulture);
                    int group = tintList.IndexOf(tint);

                    if (group < 0)
                    {
                        group = tintList.Count;
                        tintList.Add(tint);
                        corners.Add(new List<float>());
                        textureSpots.Add(new List<float>());
                    }

                    // The four corners: where each is, and its place on the atlas.
                    for (int corner = 0; corner < 4; corner++)
                    {
                        int at = 1 + corner * 5;
                        p[corner] = new Vector3(
                            float.Parse(numbers[at], CultureInfo.InvariantCulture) - shift,
                            float.Parse(numbers[at + 1], CultureInfo.InvariantCulture) - shift,
                            float.Parse(numbers[at + 2], CultureInfo.InvariantCulture) - shift);
                        t[corner] = new Vector2(
                            float.Parse(numbers[at + 3], CultureInfo.InvariantCulture),
                            float.Parse(numbers[at + 4], CultureInfo.InvariantCulture));
                    }

                    // How many pixels of the picture the face covers along each of its sides.
                    int across = 1;
                    int along = 1;

                    if (canCut && pieces < 12000)
                    {
                        across = PixelsBetween(t[0], t[1]);
                        along = PixelsBetween(t[1], t[2]);
                    }

                    // First see which pieces are solid. If they all are, keep the face whole.
                    bool[] solid = new bool[across * along];
                    int solidCount = 0;

                    for (int i = 0; i < across; i++)
                    {
                        for (int j = 0; j < along; j++)
                        {
                            Vector2 middle = Blend(t, (i + 0.5f) / across, (j + 0.5f) / along);
                            bool isSolid = !canCut || AtlasOpacity(middle) >= 26;
                            solid[i * along + j] = isSolid;

                            if (isSolid)
                            {
                                solidCount++;
                            }
                        }
                    }

                    if (solidCount == solid.Length)
                    {
                        for (int corner = 0; corner < 4; corner++)
                        {
                            AddCorner(corners[group], textureSpots[group], p[corner], t[corner]);
                        }

                        pieces++;
                        continue;
                    }

                    // Otherwise go along each row of pixels, and make one strip for every
                    // unbroken run of solid ones.
                    for (int i = 0; i < across; i++)
                    {
                        int j = 0;

                        while (j < along)
                        {
                            if (!solid[i * along + j])
                            {
                                j++;
                                continue;
                            }

                            int runStart = j;

                            while (j < along && solid[i * along + j])
                            {
                                j++;
                            }

                            float s0 = (float)i / across, s1 = (float)(i + 1) / across;
                            float r0 = (float)runStart / along, r1 = (float)j / along;

                            // The strip's place on the atlas is pulled in a touch from its
                            // edges, so it can't pick up the colour of the pixel next door.
                            float sIn = 0.02f / across, rIn = 0.02f / along;

                            AddCorner(corners[group], textureSpots[group], Blend(p, s0, r0), Blend(t, s0 + sIn, r0 + rIn));
                            AddCorner(corners[group], textureSpots[group], Blend(p, s1, r0), Blend(t, s1 - sIn, r0 + rIn));
                            AddCorner(corners[group], textureSpots[group], Blend(p, s1, r1), Blend(t, s1 - sIn, r1 - rIn));
                            AddCorner(corners[group], textureSpots[group], Blend(p, s0, r1), Blend(t, s0 + sIn, r1 - rIn));
                            pieces++;
                        }
                    }
                }

                if (tintList.Count == 0)
                {
                    return;
                }

                List<float[]> cornerArrays = new List<float[]>();
                List<float[]> spotArrays = new List<float[]>();

                for (int i = 0; i < tintList.Count; i++)
                {
                    cornerArrays.Add(corners[i].ToArray());
                    spotArrays.Add(textureSpots[i].ToArray());
                }

                mesh = BuildMesh(cornerArrays, spotArrays, mesh);
                tints = tintList.ToArray();
                cutOut = canCut;
            }
            catch (Exception e)
            {
                if (warnedMissing.Add(what))
                {
                    Logger.LogWarning("Could not read a Minecraft " + what + ": " + e.Message);
                }
            }
        }

        /// <summary>
        /// Turns lists of quads into a mesh, the form Unity draws shapes from. Each list becomes
        /// its own part of the mesh, so it can have its own material. In each list, corners are
        /// x, y, z (3 numbers each) and texture spots are u, v (2 numbers each), 4 per quad.
        /// </summary>
        private static Mesh BuildMesh(List<float[]> cornerLists, List<float[]> spotLists, Mesh refill = null)
        {
            List<Vector3> points = new List<Vector3>();
            List<Vector2> spots = new List<Vector2>();
            List<int[]> parts = new List<int[]>();

            for (int list = 0; list < cornerLists.Count; list++)
            {
                float[] corners = cornerLists[list];
                float[] uv = spotLists[list];
                int quadCount = corners.Length / 12;
                int[] triangles = new int[quadCount * 6];

                for (int quad = 0; quad < quadCount; quad++)
                {
                    int first = points.Count;

                    for (int corner = 0; corner < 4; corner++)
                    {
                        int c = (quad * 4 + corner) * 3;
                        int t = (quad * 4 + corner) * 2;

                        // z is flipped between the games.
                        points.Add(new Vector3(corners[c], corners[c + 1], -corners[c + 2]));
                        spots.Add(new Vector2(uv[t], uv[t + 1]));
                    }

                    // Unity draws triangles, so each four-cornered face becomes two. The order
                    // of the corners decides which side of a triangle is its front, and only
                    // fronts are drawn. Going round the other way from Minecraft (0, 2, 1
                    // rather than 0, 1, 2) puts the fronts on the outside of the block.
                    int at = quad * 6;
                    triangles[at] = first;
                    triangles[at + 1] = first + 2;
                    triangles[at + 2] = first + 1;
                    triangles[at + 3] = first;
                    triangles[at + 4] = first + 3;
                    triangles[at + 5] = first + 2;
                }

                parts.Add(triangles);
            }

            // Refilling a mesh that is already in use changes everything drawn with it at once.
            Mesh mesh = refill;

            if (mesh == null)
            {
                mesh = new Mesh();
            }
            else
            {
                mesh.Clear();
            }

            if (points.Count > 65000)
            {
                mesh.indexFormat = UnityEngine.Rendering.IndexFormat.UInt32;
            }

            mesh.SetVertices(points);
            mesh.SetUVs(0, spots);
            mesh.subMeshCount = parts.Count;

            for (int i = 0; i < parts.Count; i++)
            {
                mesh.SetTriangles(parts[i], i);
            }

            mesh.RecalculateNormals();
            mesh.RecalculateBounds();
            return mesh;
        }

        /// <summary>
        /// Reads Minecraft's block atlas from its file, if there is a version newer than the one
        /// already loaded, and puts it on every textured block.
        /// </summary>
        private void LoadAtlas()
        {
            try
            {
                if (!File.Exists(atlasPath))
                {
                    return;
                }

                int width, height, number;
                byte[] pixels;

                // Opened so that Minecraft can still write to it.
                using (FileStream stream = new FileStream(atlasPath, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete))
                {
                    if (ReadAll(stream, atlasHead, 16) < 16 || BitConverter.ToInt32(atlasHead, 0) != OverlayMarker)
                    {
                        return;
                    }

                    width = BitConverter.ToInt32(atlasHead, 4);
                    height = BitConverter.ToInt32(atlasHead, 8);
                    number = BitConverter.ToInt32(atlasHead, 12);

                    if (width <= 0 || height <= 0 || width > 16384 || height > 16384 || stream.Length < 16 + (long)width * height * 4)
                    {
                        return;
                    }

                    // The same version as last time: nothing to do.
                    if (blockAtlas != null && number == atlasNumber)
                    {
                        return;
                    }

                    // The same memory is used each time, unless the atlas has changed size.
                    int bytes = width * height * 4;
                    pixels = atlasPixels != null && atlasPixels.Length == bytes ? atlasPixels : new byte[bytes];

                    if (ReadAll(stream, pixels, bytes) < bytes)
                    {
                        return;
                    }
                }

                bool fresh = blockAtlas == null || blockAtlas.width != width || blockAtlas.height != height;

                if (fresh)
                {
                    blockAtlas = new Texture2D(width, height, TextureFormat.RGBA32, false);

                    // "Point" keeps Minecraft's pixels sharp instead of smoothing them together.
                    blockAtlas.filterMode = FilterMode.Point;
                    blockAtlas.wrapMode = TextureWrapMode.Clamp;
                }

                blockAtlas.LoadRawTextureData(pixels);
                blockAtlas.Apply(false);
                atlasPixels = pixels;
                atlasWidth = width;
                atlasHeight = height;
                atlasNumber = number;

                if (fresh)
                {
                    foreach (Material material in texturedMaterials.Values)
                    {
                        if (material != null)
                        {
                            material.mainTexture = blockAtlas;
                        }
                    }

                    foreach (Material material in glowMaterials.Values)
                    {
                        if (material != null)
                        {
                            material.mainTexture = blockAtlas;
                            SetTextureIfPresent(material, "_Illum", blockAtlas);
                        }
                    }

                    foreach (Material material in waterMaterials.Values)
                    {
                        if (material != null)
                        {
                            material.mainTexture = blockAtlas;
                        }
                    }

                    Logger.LogInfo("Loaded Minecraft's block atlas, " + width + " x " + height);
                }

                // Shapes are cut along the atlas's see-through pixels. Remake the ones made
                // before the atlas arrived, and the ones whose picture moves.
                foreach (BlockModel model in blockModels.Values)
                {
                    if (model.quads.Length > 0 && (model.animated || !model.cutOut))
                    {
                        CutModel(model.quads, 0f, ref model.mesh, ref model.tints, ref model.cutOut, "block model");
                    }
                }
            }
            catch (Exception e)
            {
                if (warnedMissing.Add("atlas"))
                {
                    Logger.LogWarning("Could not load Minecraft's block atlas: " + e.Message);
                }
            }
        }

        /// <summary>Reads the given number of bytes from a file, or as many as there are. Returns how many it got.</summary>
        private static int ReadAll(Stream stream, byte[] into, int count)
        {
            int read = 0;

            while (read < count)
            {
                int got = stream.Read(into, read, count - read);

                if (got <= 0)
                {
                    break;
                }

                read += got;
            }

            return read;
        }

        // ---- Cracks -----------------------------------------------------------------------------

        /// <summary>"CRACKS" then forty numbers: where each of the ten cracking pictures is on the atlas.</summary>
        private void HandleCracks(string text)
        {
            string[] parts = text.Trim().Split(' ');

            if (parts.Length != 40)
            {
                return;
            }

            float[] spots = new float[40];

            for (int i = 0; i < 40; i++)
            {
                if (!float.TryParse(parts[i], NumberStyles.Float, CultureInfo.InvariantCulture, out spots[i]))
                {
                    return;
                }
            }

            crackSpots = spots;

            for (int i = 0; i < crackMeshes.Length; i++)
            {
                crackMeshes[i] = null;
            }
        }

        /// <summary>
        /// "x y z stage": the block there is being broken and is at this stage (0 to 9) of
        /// cracking; -1 means the cracks should go. They are drawn as a shell just outside the
        /// block's solid shape, made from the cracking picture with its see-through pixels cut out.
        /// </summary>
        private void HandleCrack(string text)
        {
            string[] parts = text.Split(' ');
            int x, y, z, stage;
            int who = 0;

            if ((parts.Length != 4 && parts.Length != 5)
                || !int.TryParse(parts[0], NumberStyles.Integer, CultureInfo.InvariantCulture, out x)
                || !int.TryParse(parts[1], NumberStyles.Integer, CultureInfo.InvariantCulture, out y)
                || !int.TryParse(parts[2], NumberStyles.Integer, CultureInfo.InvariantCulture, out z)
                || !int.TryParse(parts[3], NumberStyles.Integer, CultureInfo.InvariantCulture, out stage)
                || (parts.Length == 5 && !int.TryParse(parts[4], NumberStyles.Integer, CultureInfo.InvariantCulture, out who)))
            {
                return;
            }

            // Each player breaking a block has their own set of cracks.
            Cracks shown;

            if (!cracks.TryGetValue(who, out shown))
            {
                shown = new Cracks();
                cracks[who] = shown;
            }

            if (stage < 0 || stage > 9 || crackSpots == null || atlasPixels == null)
            {
                Hide(shown);
                return;
            }

            try
            {
                if (crackMeshes[stage] == null)
                {
                    // A cube whose every face shows this stage's picture.
                    float u0 = crackSpots[stage * 4], v0 = crackSpots[stage * 4 + 1];
                    float u1 = crackSpots[stage * 4 + 2], v1 = crackSpots[stage * 4 + 3];
                    StringBuilder quads = new StringBuilder();

                    for (int face = 0; face < 6; face++)
                    {
                        if (face > 0)
                        {
                            quads.Append(';');
                        }

                        quads.Append("FFFFFF");

                        for (int corner = 0; corner < 4; corner++)
                        {
                            int at = face * 12 + corner * 3;
                            float u = corner < 2 ? u0 : u1;
                            float v = corner == 0 || corner == 3 ? v0 : v1;
                            quads.Append(string.Format(CultureInfo.InvariantCulture, ",{0},{1},{2},{3:0.######},{4:0.######}",
                                CubeFaces[at], CubeFaces[at + 1], CubeFaces[at + 2], u, v));
                        }
                    }

                    int[] unusedTints = null;
                    bool unusedCut = false;
                    CutModel(quads.ToString(), 0.5f, ref crackMeshes[stage], ref unusedTints, ref unusedCut, "cracks");

                    if (crackMeshes[stage] == null)
                    {
                        return;
                    }
                }

                GameObject crackThing = shown.thing;

                if (crackThing == null)
                {
                    crackThing = new GameObject("MinecraftCracks");
                    crackThing.AddComponent<MeshFilter>();

                    // Darkened, so the cracks read as cracks whatever colour the picture is.
                    crackThing.AddComponent<MeshRenderer>().sharedMaterial = TexturedMaterial(0x303030);
                    ApplySubnauticaLighting(crackThing);
                    shown.thing = crackThing;
                }

                // Fit the shell to the block's solid shape (a slab's is half height), a touch
                // bigger so it sits just outside the surface.
                Vector3 middle = new Vector3(0.5f, 0.5f, -0.5f);
                Vector3 size = Vector3.one;
                long key = ((long)(x + 0x100000) << 42) | ((long)(y + 0x100000) << 21) | (long)(z + 0x100000);
                GameObject block;

                if (blocks.TryGetValue(key, out block) && block != null)
                {
                    BoxCollider solid = block.GetComponent<BoxCollider>();

                    if (solid != null)
                    {
                        middle = solid.center;
                        size = solid.size;
                    }
                }

                shown.key = key;
                crackThing.GetComponent<MeshFilter>().sharedMesh = crackMeshes[stage];
                crackThing.transform.position = new Vector3(x, y, -z) + middle;
                crackThing.transform.localScale = size * 1.02f;
                crackThing.SetActive(true);
            }
            catch (Exception e)
            {
                if (warnedMissing.Add("cracks error"))
                {
                    Logger.LogWarning("Could not show block-breaking cracks: " + e);
                }
            }
        }

        private static void Hide(Cracks shown)
        {
            shown.key = -1;

            if (shown.thing != null)
            {
                shown.thing.SetActive(false);
            }
        }

        private void HideCracks()
        {
            foreach (Cracks shown in cracks.Values)
            {
                Hide(shown);
            }
        }

        /// <summary>The block at this position has gone or changed: any cracks on it go too.</summary>
        private void HideCracksAt(long key)
        {
            foreach (Cracks shown in cracks.Values)
            {
                if (shown.key == key)
                {
                    Hide(shown);
                }
            }
        }

        /// <summary>
        /// "cx cz": that 16-by-16 column of the Minecraft world is no longer being described by
        /// this player's game (they moved away from it), so its blocks are taken down. They are
        /// sent again if the player comes back.
        /// </summary>
        private void HandleChunkGone(string text)
        {
            string[] parts = text.Split(' ');
            int cx, cz;

            if (parts.Length != 2
                || !int.TryParse(parts[0], NumberStyles.Integer, CultureInfo.InvariantCulture, out cx)
                || !int.TryParse(parts[1], NumberStyles.Integer, CultureInfo.InvariantCulture, out cz))
            {
                return;
            }

            List<long> gone = new List<long>();

            foreach (KeyValuePair<long, GameObject> entry in blocks)
            {
                // Unpack the block's position from its key (see HandleBlock), then which
                // column that is: ">> 4" is "divide by 16, rounding down".
                int x = (int)((entry.Key >> 42) & 0x1FFFFF) - 0x100000;
                int z = (int)(entry.Key & 0x1FFFFF) - 0x100000;

                if ((x >> 4) == cx && (z >> 4) == cz)
                {
                    gone.Add(entry.Key);
                }
            }

            foreach (long key in gone)
            {
                GameObject block = blocks[key];

                if (block != null)
                {
                    block.SetActive(false);
                    Destroy(block);
                }

                blocks.Remove(key);
                HideCracksAt(key);
            }
        }

        // ---- Explosions -------------------------------------------------------------------------

        /// <summary>
        /// "x y z power": something exploded in Minecraft (TNT has power 4). Minecraft deals with
        /// its own blocks and the player; here the blast hurts Subnautica's creatures, using
        /// Minecraft's own sums: it reaches twice the power in metres, and does most at the centre.
        /// </summary>
        private void HandleBlast(string numbers, Player player)
        {
            string[] parts = numbers.Split(' ');
            float x, y, z, power;

            if (player == null || parts.Length != 4
                || !float.TryParse(parts[0], NumberStyles.Float, CultureInfo.InvariantCulture, out x)
                || !float.TryParse(parts[1], NumberStyles.Float, CultureInfo.InvariantCulture, out y)
                || !float.TryParse(parts[2], NumberStyles.Float, CultureInfo.InvariantCulture, out z)
                || !float.TryParse(parts[3], NumberStyles.Float, CultureInfo.InvariantCulture, out power)
                || power <= 0f)
            {
                return;
            }

            Vector3 point = new Vector3(x, y, -z);
            PlayEffect("blast", point, Vector3.up, player);
            float reach = Mathf.Min(power * 2f, 40f);
            HashSet<LiveMixin> done = new HashSet<LiveMixin>();
            int hurt = 0;

            foreach (Collider collider in Physics.OverlapSphere(point, reach, ~0, QueryTriggerInteraction.Ignore))
            {
                if (collider == null || collider.transform.IsChildOf(player.transform) || collider.GetComponentInParent<Creature>() == null)
                {
                    continue;
                }

                LiveMixin life = collider.GetComponentInParent<LiveMixin>();

                if (life == null || life == player.liveMixin || life.health <= 0f || !done.Add(life))
                {
                    continue;
                }

                float distance = (collider.ClosestPointOnBounds(point) - point).magnitude;
                float impact = 1f - distance / reach;

                if (impact <= 0f)
                {
                    continue;
                }

                // Minecraft's damage, then five for one like all damage that crosses over.
                float damage = ((impact * impact + impact) / 2f * 7f * reach + 1f) * 5f;
                life.TakeDamage(damage, point, DamageType.Normal, player.gameObject);
                hurt++;
            }

            Logger.LogInfo(string.Format(CultureInfo.InvariantCulture, "Explosion of power {0:0.#} at ({1:0.#}, {2:0.#}, {3:0.#}) hurt {4} creature(s)", power, point.x, point.y, point.z, hurt));
        }

        /// <summary>The material for a textured part of a block with the given tint (FFFFFF for none).</summary>
        private Material TexturedMaterial(int tint)
        {
            Material material;

            if (texturedMaterials.TryGetValue(tint, out material) && material != null)
            {
                return material;
            }

            // Leaves, glass, flowers and torches have see-through pixels, and the shader has to
            // be told to leave those out. Setting that option on a fresh material didn't work:
            // a shader only comes in the combinations of options the game was built with, and
            // the fresh one wasn't among them. So instead, copy one of Subnautica's OWN
            // see-through materials (a plant's, say), which is certain to be a combination that
            // exists, and swap its pictures and colours for the block's.
            Material template = FindCutoutTemplate();

            if (template != null)
            {
                material = new Material(template);
                material.name = "MinecraftBlock";
                material.mainTextureScale = Vector2.one;
                material.mainTextureOffset = Vector2.zero;
                material.color = new Color(((tint >> 16) & 0xFF) / 255f, ((tint >> 8) & 0xFF) / 255f, (tint & 0xFF) / 255f, 1f);

                // Replace the plant's extra pictures with blank ones: no shine, no glow, no bumps.
                SetTextureIfPresent(material, "_SpecTex", Texture2D.blackTexture);
                SetTextureIfPresent(material, "_Illum", Texture2D.blackTexture);
                SetTextureIfPresent(material, "_BumpMap", FlatBumpTexture());

                foreach (string setting in new[] { "_GlowStrength", "_GlowStrengthNight", "_EmissionLM", "_EmissionLMNight", "_SpecInt", "_Fresnel" })
                {
                    if (material.HasProperty(setting))
                    {
                        material.SetFloat(setting, 0f);
                    }
                }

                // Plants sway. Blocks shouldn't: set the amount of sway to nothing.
                foreach (string setting in new[] { "_Scale", "_Frequency", "_Speed" })
                {
                    if (material.HasProperty(setting))
                    {
                        try
                        {
                            material.SetVector(setting, Vector4.zero);
                        }
                        catch (Exception)
                        {
                            // Not that kind of setting in this version; leave it.
                        }
                    }
                }
            }
            else
            {
                // None found (yet). Fall back to a fresh material with the options set by hand.
                Material plain = BlockMaterial(tint, null);

                if (plain == null)
                {
                    return null;
                }

                material = new Material(plain);

                // Named as the mod's own, so the search for one of Subnautica's materials to copy passes it over.
                material.name = "MinecraftBlock";
                material.EnableKeyword("MARMO_ALPHA_CLIP");
                material.EnableKeyword("MARMO_SPECMAP");
                material.EnableKeyword("_ZWRITE_ON");
                SetTextureIfPresent(material, "_SpecTex", Texture2D.blackTexture);
            }

            material.mainTexture = blockAtlas != null ? (Texture)blockAtlas : Texture2D.whiteTexture;

            // Pixels less than half opaque are left out.
            if (material.HasProperty("_Cutoff"))
            {
                material.SetFloat("_Cutoff", 0.5f);
            }

            // The place in the drawing order Unity uses for clipped materials.
            material.renderQueue = 2450;

            // Without a template, try again for the next block rather than keeping this one.
            if (template == null)
            {
                return material;
            }

            texturedMaterials[tint] = material;
            return material;
        }

        // ---- See-through water ---------------------------------------------------------------------
        //
        // Poured water is drawn with one of Unity's own plain see-through shaders (the one it
        // draws flat pictures with), which every Unity game carries. Subnautica's own shader
        // was tried first, by copying one of its glass materials, but none turned up to copy.
        // The plain shader knows nothing of Subnautica's lighting, so the water's brightness
        // is set from the time of day here: without that it would glow at night.

        private readonly Dictionary<int, Material> waterMaterials = new Dictionary<int, Material>();
        private Shader waterShader;
        private bool waterShaderLooked;
        private float nextWaterLight;

        /// <summary>How solid poured water looks: 0 is invisible, 1 is solid.</summary>
        private const float WaterOpacity = 0.65f;

        private Material WaterMaterial(int tint)
        {
            Material material;

            if (waterMaterials.TryGetValue(tint, out material) && material != null)
            {
                return material;
            }

            if (!waterShaderLooked)
            {
                waterShaderLooked = true;

                foreach (string name in new[] { "Sprites/Default", "UI/Default", "Unlit/Transparent", "Legacy Shaders/Transparent/Diffuse" })
                {
                    waterShader = Shader.Find(name);

                    if (waterShader != null)
                    {
                        Logger.LogInfo("Drawing see-through water with the shader " + name);
                        break;
                    }
                }

                if (waterShader == null)
                {
                    Logger.LogWarning("No see-through shader was found; water is drawn solid");
                }
            }

            if (waterShader == null)
            {
                return TexturedMaterial(tint);
            }

            material = new Material(waterShader);
            material.name = "MinecraftBlock";
            material.mainTexture = blockAtlas != null ? (Texture)blockAtlas : Texture2D.whiteTexture;
            material.color = WaterColour(tint, WaterDaylight());

            // After everything solid, with the other see-through things.
            material.renderQueue = 3000;
            waterMaterials[tint] = material;
            return material;
        }

        private static Color WaterColour(int tint, float daylight)
        {
            return new Color(((tint >> 16) & 0xFF) / 255f * daylight, ((tint >> 8) & 0xFF) / 255f * daylight, (tint & 0xFF) / 255f * daylight, WaterOpacity);
        }

        /// <summary>How bright the day is, from a dim glimmer at night to 1 at noon.</summary>
        private static float WaterDaylight()
        {
            try
            {
                DayNightCycle cycle = DayNightCycle.main;
                return cycle != null ? Mathf.Lerp(0.12f, 1f, Mathf.Clamp01(cycle.GetLocalLightScalar())) : 1f;
            }
            catch (Exception)
            {
                return 1f;
            }
        }

        /// <summary>Every frame (it does something twice a second): keeps poured water as bright as the time of day.</summary>
        private void TendWaterLight()
        {
            if (waterMaterials.Count == 0 || Time.unscaledTime < nextWaterLight)
            {
                return;
            }

            nextWaterLight = Time.unscaledTime + 0.5f;
            float daylight = WaterDaylight();

            foreach (KeyValuePair<int, Material> entry in waterMaterials)
            {
                if (entry.Value != null)
                {
                    entry.Value.color = WaterColour(entry.Key, daylight);
                }
            }
        }

        // ---- Blocks and the Cyclops ---------------------------------------------------------------
        //
        // A Minecraft block is a fixed, immovable thing to Unity's physics, and the Cyclops is a
        // floating one. A block put down inside it was a rock wedged in its hull: the sub was
        // thrown about until it broke up. So Minecraft's blocks and the Cyclops's solid parts
        // are told to pass through each other. Blocks can still be placed aboard (they stay
        // where they are in the world; they don't travel with the sub), and the player still
        // stands on them, since that is worked out separately.

        private class SubHull
        {
            public SubRoot sub;
            public readonly List<Collider> parts = new List<Collider>();

            /// <summary>The blocks already told to pass through this hull.</summary>
            public readonly HashSet<Collider> eased = new HashSet<Collider>();
        }

        private readonly List<SubHull> cyclopsHulls = new List<SubHull>();
        private readonly List<SubHull> hullsBefore = new List<SubHull>();
        private float nextBlockEase;

        /// <summary>The space a Cyclops takes up just now, with room to spare all round. False if it has gone.</summary>
        private static bool HullSpace(SubHull hull, float spare, out Bounds space)
        {
            space = new Bounds();
            bool any = false;

            if (hull.sub == null)
            {
                return false;
            }

            foreach (Collider part in hull.parts)
            {
                if (part == null || !part.enabled)
                {
                    continue;
                }

                if (any)
                {
                    space.Encapsulate(part.bounds);
                }
                else
                {
                    space = part.bounds;
                    any = true;
                }
            }

            space.Expand(spare * 2f);
            return any;
        }

        /// <summary>Tells one block and every solid part of one Cyclops to pass through each other.</summary>
        private void EaseBlock(Collider block, SubHull hull)
        {
            foreach (Collider part in hull.parts)
            {
                if (part != null && part.enabled && part.gameObject.activeInHierarchy)
                {
                    Physics.IgnoreCollision(block, part, true);
                }
            }
        }

        /// <summary>A block has just been built: if it is in or near a Cyclops, it is dealt with before it can touch the hull.</summary>
        private void EaseNewBlock(Collider block)
        {
            try
            {
                foreach (SubHull hull in cyclopsHulls)
                {
                    Bounds space;

                    if (HullSpace(hull, 6f, out space) && space.Contains(block.bounds.center))
                    {
                        EaseBlock(block, hull);
                        hull.eased.Add(block);
                    }
                }
            }
            catch (Exception e)
            {
                if (warnedMissing.Add("block in sub"))
                {
                    Logger.LogWarning("Could not stop a block pressing on the Cyclops: " + e.Message);
                }
            }
        }

        /// <summary>Every frame (it does something a few times a second): blocks a Cyclops has come near are dealt with before it reaches them.</summary>
        private void TendBlocksNearSubs()
        {
            if (cyclopsHulls.Count == 0 || blocks.Count == 0 || Time.unscaledTime < nextBlockEase)
            {
                return;
            }

            nextBlockEase = Time.unscaledTime + 0.3f;

            try
            {
                foreach (SubHull hull in cyclopsHulls)
                {
                    Bounds space;
                    hull.eased.RemoveWhere(gone => gone == null);

                    if (!HullSpace(hull, 6f, out space))
                    {
                        continue;
                    }

                    foreach (Collider near in Physics.OverlapBox(space.center, space.extents, Quaternion.identity, 1 << LayerID.TerrainCollider, QueryTriggerInteraction.Ignore))
                    {
                        if (near != null && IsMinecraftBlock(near) && hull.eased.Add(near))
                        {
                            EaseBlock(near, hull);
                        }
                    }
                }
            }
            catch (Exception e)
            {
                if (warnedMissing.Add("blocks near sub"))
                {
                    Logger.LogWarning("Could not stop blocks pressing on the Cyclops: " + e.Message);
                }
            }
        }

        // ---- Light from what other players hold -------------------------------------------------
        //
        // "PLIGHT id range strength colour flicker" (as for a block's light) or "PLIGHT id 0":
        // the thing in another player's hand gives this light. A light is kept at that player's
        // chest for as long as they are being drawn.

        private class PlayerLight
        {
            public Light light;
            public float strength;
            public bool flickers;
        }

        private readonly Dictionary<int, PlayerLight> playerLights = new Dictionary<int, PlayerLight>();
        private readonly List<int> playerLightScratch = new List<int>();

        private void HandlePlayerLight(string text)
        {
            string[] parts = text.Split(' ');
            int id, colour, flicker;
            float range, strength;

            if (parts.Length < 2 || !int.TryParse(parts[0], NumberStyles.Integer, CultureInfo.InvariantCulture, out id))
            {
                return;
            }

            PlayerLight held;
            playerLights.TryGetValue(id, out held);

            if (parts.Length != 5
                || !float.TryParse(parts[1], NumberStyles.Float, CultureInfo.InvariantCulture, out range)
                || !float.TryParse(parts[2], NumberStyles.Float, CultureInfo.InvariantCulture, out strength)
                || !int.TryParse(parts[3], NumberStyles.HexNumber, CultureInfo.InvariantCulture, out colour)
                || !int.TryParse(parts[4], NumberStyles.Integer, CultureInfo.InvariantCulture, out flicker))
            {
                // "PLIGHT id 0", or anything unreadable: no light.
                if (held != null)
                {
                    if (held.light != null)
                    {
                        Destroy(held.light.gameObject);
                    }

                    playerLights.Remove(id);
                }

                return;
            }

            if (held == null || held.light == null)
            {
                held = new PlayerLight();
                GameObject holder = new GameObject("MinecraftPlayerLight");
                held.light = holder.AddComponent<Light>();
                held.light.type = LightType.Point;
                held.light.shadows = LightShadows.None;
                held.light.enabled = false;
                playerLights[id] = held;
            }

            held.light.range = Mathf.Clamp(range, 1f, 40f) * LightReachBoost;
            held.strength = Mathf.Clamp(strength, 0.1f, 3f) * LightBoost;
            held.light.intensity = held.strength;
            held.light.color = new Color(((colour >> 16) & 0xFF) / 255f, ((colour >> 8) & 0xFF) / 255f, (colour & 0xFF) / 255f, 1f);
            held.flickers = flicker != 0;
        }

        /// <summary>Every frame: keep each other player's light on them, and make a flame flicker.</summary>
        private void TendPlayerLights()
        {
            if (playerLights.Count == 0)
            {
                return;
            }

            playerLightScratch.Clear();

            foreach (KeyValuePair<int, PlayerLight> entry in playerLights)
            {
                PlayerLight held = entry.Value;

                if (held.light == null)
                {
                    playerLightScratch.Add(entry.Key);
                    continue;
                }

                Avatar avatar;
                bool on = linked && avatars.TryGetValue(entry.Key, out avatar) && avatar.thing != null;

                if (held.light.enabled != on)
                {
                    held.light.enabled = on;
                }

                if (!on)
                {
                    continue;
                }

                // (Looked up again: C# won't let "avatar" be used here if the test above stopped short.)
                Avatar shown = avatars[entry.Key];
                held.light.transform.position = shown.shownPlace + Vector3.up * 1.3f;
                held.light.intensity = held.flickers ? held.strength * (0.82f + 0.3f * Mathf.PerlinNoise(Time.time * 6f, entry.Key * 0.37f)) : held.strength;
            }

            foreach (int id in playerLightScratch)
            {
                playerLights.Remove(id);
            }
        }

        private void RemovePlayerLights()
        {
            foreach (PlayerLight held in playerLights.Values)
            {
                if (held.light != null)
                {
                    Destroy(held.light.gameObject);
                }
            }

            playerLights.Clear();
        }

        // ---- The scroll wheel over a Minecraft screen -------------------------------------------------

        private PropertyInfo wheelProperty;
        private bool wheelLooked;

        /// <summary>How far the scroll wheel has turned this frame (up is positive), asked of Unity directly.</summary>
        private float WheelTurned()
        {
            try
            {
                if (!wheelLooked)
                {
                    wheelLooked = true;

                    // Unity's "Input" lives in a part of Unity this mod isn't built against, so it is found by name.
                    foreach (Assembly loaded in AppDomain.CurrentDomain.GetAssemblies())
                    {
                        Type input = loaded.GetType("UnityEngine.Input", false);

                        if (input != null)
                        {
                            wheelProperty = input.GetProperty("mouseScrollDelta", BindingFlags.Static | BindingFlags.Public);

                            if (wheelProperty != null)
                            {
                                break;
                            }
                        }
                    }

                    if (wheelProperty == null)
                    {
                        Logger.LogWarning("Could not find the scroll wheel; it won't scroll Minecraft's screens");
                    }
                }

                return wheelProperty != null ? ((Vector2)wheelProperty.GetValue(null, null)).y : 0f;
            }
            catch (Exception)
            {
                return 0f;
            }
        }

        // ---- Lava for the bucket ---------------------------------------------------------------------

        private LavaDatabase lavaDatabase;

        /// <summary>
        /// "LAVACHECK": Minecraft's player has used an empty bucket in the lava zones. Is it
        /// Subnautica's lava they are looking at, within reach? Answered "LAVASCOOP 1" or
        /// "LAVASCOOP 0". Subnautica keeps a list of which kinds of ground are lava (it is how it
        /// knows when to burn you); that list is asked about the ground in the line of sight.
        /// </summary>
        private void AnswerLavaCheck(Player player)
        {
            bool lava = false;

            try
            {
                Camera view = Camera.main;

                if (view != null && player != null && !WorldLoading())
                {
                    if (lavaDatabase == null)
                    {
                        LavaDatabase[] found = Resources.FindObjectsOfTypeAll<LavaDatabase>();

                        if (found.Length > 0)
                        {
                            lavaDatabase = found[0];
                        }
                    }

                    RaycastHit ground;

                    if (lavaDatabase != null
                        && Physics.Raycast(view.transform.position, view.transform.forward, out ground, 6f, 1 << LayerID.TerrainCollider, QueryTriggerInteraction.Ignore)
                        && !IsMinecraftBlock(ground.collider))
                    {
                        lava = LavaDatabaseUtils.IsLava(lavaDatabase, ground.point, ground.normal);
                    }
                }
            }
            catch (Exception e)
            {
                if (warnedMissing.Add("lava check"))
                {
                    Logger.LogWarning("Could not tell whether that is lava: " + e);
                }
            }

            Send("LAVASCOOP " + (lava ? "1" : "0"));
        }

        private static void SetTextureIfPresent(Material material, string name, Texture texture)
        {
            if (material.HasProperty(name))
            {
                material.SetTexture(name, texture);
            }
        }

        /// <summary>A one-pixel "bump map" that says "perfectly flat", made the first time it is needed.</summary>
        private Texture2D FlatBumpTexture()
        {
            if (flatBump == null)
            {
                flatBump = new Texture2D(1, 1, TextureFormat.RGBA32, false, true);
                flatBump.SetPixel(0, 0, new Color(1f, 0.5f, 1f, 0.5f));
                flatBump.Apply(false);
            }

            return flatBump;
        }

        /// <summary>
        /// Finds one of Subnautica's own materials that leaves out see-through pixels, to copy.
        /// Of those loaded, it prefers one that doesn't sway and has the fewest other options
        /// switched on, since every extra option is something to neutralise. The choice is
        /// noted in the log.
        /// </summary>
        private Material FindCutoutTemplate()
        {
            if (cutoutTemplate != null)
            {
                return cutoutTemplate;
            }

            // Looking through every loaded material isn't free, so not more than every 5 seconds.
            if (blockShader == null || Time.unscaledTime < nextTemplateSearch)
            {
                return null;
            }

            nextTemplateSearch = Time.unscaledTime + 5f;

            try
            {
                Material best = null;
                int bestScore = int.MaxValue;

                foreach (Material candidate in Resources.FindObjectsOfTypeAll<Material>())
                {
                    if (candidate == null || candidate.shader != blockShader || !candidate.IsKeywordEnabled("MARMO_ALPHA_CLIP")
                        || candidate.name == "MinecraftBlock" || blockMaterials.ContainsValue(candidate) || candidate.mainTexture == null)
                    {
                        continue;
                    }

                    int score = candidate.shaderKeywords.Length;

                    if (candidate.IsKeywordEnabled("UWE_WAVING"))
                    {
                        score += 100;
                    }

                    if (candidate.renderQueue >= 3000)
                    {
                        // Drawn as blended glass rather than clipped; not what is wanted.
                        score += 1000;
                    }

                    if (score < bestScore)
                    {
                        bestScore = score;
                        best = candidate;
                    }
                }

                if (best != null)
                {
                    cutoutTemplate = best;
                    Logger.LogInfo("Copying Subnautica's material \"" + best.name + "\" for see-through blocks: " + string.Join(" ", best.shaderKeywords)
                        + "; queue " + best.renderQueue
                        + (best.HasProperty("_Cutoff") ? "; cutoff " + best.GetFloat("_Cutoff").ToString("0.##", CultureInfo.InvariantCulture) : "; no cutoff setting"));
                }
                else if (warnedMissing.Add("cutout template"))
                {
                    Logger.LogInfo("No Subnautica see-through material is loaded yet to copy; will keep looking");
                }
            }
            catch (Exception e)
            {
                if (warnedMissing.Add("cutout template error"))
                {
                    Logger.LogWarning("Could not look for a Subnautica see-through material: " + e.Message);
                }
            }

            return cutoutTemplate;
        }

        /// <summary>The surface appearance for one colour of block, made the first time it is needed.</summary>
        private Material BlockMaterial(int colour, Material builtIn)
        {
            Material material;

            if (blockMaterials.TryGetValue(colour, out material) && material != null)
            {
                return material;
            }

            if (!blockShaderLookupDone)
            {
                blockShaderLookupDone = true;

                // "MarmosetUBER" is the shader Subnautica draws its own world with, so blocks
                // using it get the game's lighting and underwater haze. The others are fallbacks.
                foreach (string name in new[] { "MarmosetUBER", "Standard", "Legacy Shaders/Diffuse", "Unlit/Color", "Sprites/Default" })
                {
                    blockShader = Shader.Find(name);

                    if (blockShader != null)
                    {
                        Logger.LogInfo("Drawing Minecraft blocks with the shader " + name);
                        break;
                    }
                }
            }

            Color tint = new Color(((colour >> 16) & 0xFF) / 255f, ((colour >> 8) & 0xFF) / 255f, (colour & 0xFF) / 255f, 1f);

            if (blockShader == null)
            {
                // Nothing to draw with. Unity shows such objects in bright pink, which at least
                // makes the problem obvious.
                return null;
            }

            material = new Material(blockShader);
            material.color = tint;

            if (material.HasProperty("_MainTex"))
            {
                material.SetTexture("_MainTex", Texture2D.whiteTexture);
            }

            blockMaterials[colour] = material;
            return material;
        }

        /// <summary>
        /// Subnautica lights each object according to where it is (open sea, inside a base) through
        /// a small helper called SkyApplier. Give the cube one, if this version of the game has it.
        /// </summary>
        private void ApplySubnauticaLighting(GameObject cube)
        {
            try
            {
                if (skyApplierType == null)
                {
                    skyApplierType = typeof(Player).Assembly.GetType("SkyApplier");

                    if (skyApplierType == null)
                    {
                        if (warnedMissing.Add("SkyApplier"))
                        {
                            Logger.LogWarning("Could not find SkyApplier; blocks may be lit differently from their surroundings");
                        }

                        return;
                    }
                }

                Component applier = cube.AddComponent(skyApplierType);
                FieldInfo renderers = skyApplierType.GetField("renderers", BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic);

                if (renderers != null)
                {
                    renderers.SetValue(applier, new Renderer[] { cube.GetComponent<Renderer>() });
                }
            }
            catch (Exception e)
            {
                if (warnedMissing.Add("SkyApplier error"))
                {
                    Logger.LogWarning("Could not set up Subnautica's lighting on blocks: " + e.Message);
                }
            }
        }

        private void RemoveAllBlocks()
        {
            foreach (GameObject cube in blocks.Values)
            {
                if (cube != null)
                {
                    Destroy(cube);
                }
            }

            blocks.Clear();

            // Minecraft describes the models again when it reconnects. (Unity doesn't tidy
            // shapes away by itself: each has to be destroyed.)
            foreach (BlockModel old in blockModels.Values)
            {
                if (old != null && old.mesh != null)
                {
                    Destroy(old.mesh);
                }
            }

            blockModels.Clear();
            blockLights.Clear();
            lights.Clear();

            HideCracks();

            // The same goes for dropped items.
            foreach (ShownItem item in shownItems.Values)
            {
                if (item.thing != null)
                {
                    Destroy(item.thing);
                }
            }

            shownItems.Clear();

            foreach (ItemModel old in itemModels.Values)
            {
                if (old != null && old.mesh != null)
                {
                    Destroy(old.mesh);
                }
            }

            itemModels.Clear();
            LegsNeedSettling();

            // And the players.
            RemoveAllAvatars();
        }

        // ---- Dropped items --------------------------------------------------------------------

        /// <summary>"kind scale colour quads": what one kind of item looks like. The quads are as for blocks.</summary>
        private void HandleItemModel(string text)
        {
            string[] parts = text.Split(new[] { ' ' }, 4);
            int kind, colour;
            float scale;

            if (parts.Length < 3
                || !int.TryParse(parts[0], NumberStyles.Integer, CultureInfo.InvariantCulture, out kind)
                || !float.TryParse(parts[1], NumberStyles.Float, CultureInfo.InvariantCulture, out scale)
                || !int.TryParse(parts[2], NumberStyles.HexNumber, CultureInfo.InvariantCulture, out colour))
            {
                return;
            }

            ItemModel model = new ItemModel();
            model.scale = Mathf.Clamp(scale, 0.05f, 1f);
            model.colour = colour;
            model.quads = parts.Length == 4 ? parts[3].Trim() : "";
            itemModels[kind] = model;
        }

        /// <summary>"id kind x y z": a dropped item is here (feet position, Minecraft's coordinates).</summary>
        private void HandleItem(string text)
        {
            string[] parts = text.Split(' ');
            int id, kind;
            float x, y, z;

            if (parts.Length != 5
                || !int.TryParse(parts[0], NumberStyles.Integer, CultureInfo.InvariantCulture, out id)
                || !int.TryParse(parts[1], NumberStyles.Integer, CultureInfo.InvariantCulture, out kind)
                || !float.TryParse(parts[2], NumberStyles.Float, CultureInfo.InvariantCulture, out x)
                || !float.TryParse(parts[3], NumberStyles.Float, CultureInfo.InvariantCulture, out y)
                || !float.TryParse(parts[4], NumberStyles.Float, CultureInfo.InvariantCulture, out z))
            {
                return;
            }

            // z runs the other way in Subnautica.
            Vector3 spot = new Vector3(x, y, -z);
            ShownItem item;

            if (shownItems.TryGetValue(id, out item) && item.thing != null)
            {
                item.target = spot;
                return;
            }

            try
            {
                if (itemRoot == null)
                {
                    itemRoot = new GameObject("MinecraftItems");
                }

                item = new ShownItem();
                item.kind = kind;
                item.target = spot;

                // Each starts at a different angle (decided by its number), as in Minecraft.
                item.turn = (id * 47) % 360;
                item.thing = new GameObject("MinecraftItem");
                item.thing.transform.SetParent(itemRoot.transform, false);
                item.thing.AddComponent<MeshFilter>();
                item.thing.AddComponent<MeshRenderer>();
                DressItem(item);
                item.thing.transform.position = spot + Vector3.up * (item.scale / 2f + 0.1f);
                ApplySubnauticaLighting(item.thing);
                shownItems[id] = item;
            }
            catch (Exception e)
            {
                if (warnedMissing.Add("item"))
                {
                    Logger.LogWarning("Could not show a Minecraft item: " + e);
                }
            }
        }

        /// <summary>"id": that item has been picked up or has gone.</summary>
        private void HandleItemGone(string text)
        {
            int id;
            ShownItem item;

            if (int.TryParse(text.Trim(), NumberStyles.Integer, CultureInfo.InvariantCulture, out id) && shownItems.TryGetValue(id, out item))
            {
                if (item.thing != null)
                {
                    Destroy(item.thing);
                }

                shownItems.Remove(id);
            }
        }

        /// <summary>Gives a shown item its shape and materials, from the model for its kind.</summary>
        private void DressItem(ShownItem item)
        {
            MeshFilter shape = item.thing.GetComponent<MeshFilter>();
            MeshRenderer renderer = item.thing.GetComponent<MeshRenderer>();
            ItemModel model;
            itemModels.TryGetValue(item.kind, out model);

            item.dressedWithAtlas = atlasPixels != null;
            item.scale = model != null ? model.scale : 0.25f;

            if (model != null && model.quads.Length > 0 && (model.mesh == null || (!model.cutOut && atlasPixels != null)))
            {
                BuildItemMesh(model);
            }

            if (model != null && model.mesh != null)
            {
                Material[] materials = new Material[model.tints.Length];

                for (int i = 0; i < materials.Length; i++)
                {
                    materials[i] = TexturedMaterial(model.tints[i]);
                }

                shape.sharedMesh = model.mesh;
                renderer.sharedMaterials = materials;
            }
            else
            {
                if (plainItemCube == null)
                {
                    float[] centred = new float[CubeFaces.Length];

                    for (int i = 0; i < centred.Length; i++)
                    {
                        centred[i] = CubeFaces[i] - 0.5f;
                    }

                    plainItemCube = BuildMesh(new List<float[]> { centred }, new List<float[]> { new float[48] });
                }

                shape.sharedMesh = plainItemCube;
                renderer.sharedMaterial = BlockMaterial(model != null ? model.colour : 0xB0B0B0, null);
            }

            // A flat item flying point-first (an arrow, a knife) is drawn nearer its real length.
            if (item.style == 1 && item.scale > 0.4f)
            {
                item.scale *= 1.6f;
            }

            // Lit TNT is a whole block, not a dropped one.
            if (item.style == 2)
            {
                item.scale = 0.98f;
            }

            item.thing.transform.localScale = Vector3.one * item.scale;

            // Freshly dressed in its own materials: if it was flashing white, it isn't now.
            item.flashing = false;
            item.ownMaterials = null;
        }

        /// <summary>Builds the shape for one kind of item, centred on its middle so that it turns about it.</summary>
        private void BuildItemMesh(ItemModel model)
        {
            CutModel(model.quads, 0.5f, ref model.mesh, ref model.tints, ref model.cutOut, "item model");
        }

        private static void AddCorner(List<float> corners, List<float> spots, Vector3 place, Vector2 spot)
        {
            corners.Add(place.x);
            corners.Add(place.y);
            corners.Add(place.z);
            spots.Add(spot.x);
            spots.Add(spot.y);
        }

        /// <summary>How many atlas pixels lie between two places on the atlas, at least 1.</summary>
        private int PixelsBetween(Vector2 from, Vector2 to)
        {
            float pixels = Mathf.Max(Mathf.Abs(to.x - from.x) * atlasWidth, Mathf.Abs(to.y - from.y) * atlasHeight);
            return Mathf.Clamp(Mathf.RoundToInt(pixels), 1, MostPiecesPerSide);
        }

        /// <summary>A point inside a four-cornered face: s of the way from corner 0 to 1, r of the way from 0 to 3.</summary>
        private static Vector3 Blend(Vector3[] c, float s, float r)
        {
            return Vector3.Lerp(Vector3.Lerp(c[0], c[1], s), Vector3.Lerp(c[3], c[2], s), r);
        }

        private static Vector2 Blend(Vector2[] c, float s, float r)
        {
            return Vector2.Lerp(Vector2.Lerp(c[0], c[1], s), Vector2.Lerp(c[3], c[2], s), r);
        }

        /// <summary>How solid the atlas is at a place on it: 0 for see-through to 255 for solid.</summary>
        private int AtlasOpacity(Vector2 spot)
        {
            int x = Mathf.Clamp((int)(spot.x * atlasWidth), 0, atlasWidth - 1);
            int y = Mathf.Clamp((int)(spot.y * atlasHeight), 0, atlasHeight - 1);
            return atlasPixels[(y * atlasWidth + x) * 4 + 3];
        }

        /// <summary>Every frame: glide each shown item towards where Minecraft says it is, turning and bobbing.</summary>
        private void MoveShownItems()
        {
            if (shownItems.Count == 0)
            {
                return;
            }

            float glide = 1f - Mathf.Exp(-15f * Time.unscaledDeltaTime);

            foreach (KeyValuePair<int, ShownItem> entry in shownItems)
            {
                ShownItem item = entry.Value;

                if (item.thing == null)
                {
                    continue;
                }

                // The atlas arrived after this item did: give it its proper look now.
                if (!item.dressedWithAtlas && atlasPixels != null)
                {
                    DressItem(item);
                }

                if (item.style >= 0)
                {
                    if (item.style == 2)
                    {
                        FlashTnt(item);
                    }

                    MoveShownShot(item);
                    WatchForSplash(item);

                    // A fast projectile leaves bubbles behind it underwater.
                    if (item.style == 1 && item.speed.sqrMagnitude > 25f && Time.unscaledTime >= item.nextBubbles && IsUnderwater(item.thing.transform.position))
                    {
                        item.nextBubbles = Time.unscaledTime + 0.15f;
                        PlayBubbles(item.thing.transform.position);
                    }

                    continue;
                }

                // Minecraft's own turning speed (about one turn every six seconds) and gentle bob.
                item.turn = (item.turn + 57.3f * Time.deltaTime) % 360f;
                float bob = Mathf.Sin(Time.time * 2f + entry.Key) * 0.05f;
                Vector3 place = item.target + Vector3.up * (item.scale / 2f + 0.1f + bob);
                Transform spot = item.thing.transform;

                // A big jump (the first position, or a teleport) is made at once.
                spot.position = (spot.position - place).sqrMagnitude > 16f ? place : Vector3.Lerp(spot.position, place, glide);
                spot.rotation = Quaternion.Euler(0f, item.turn, 0f);
                WatchForSplash(item);
            }
        }

        /// <summary>
        /// Lit TNT flashes white, as in Minecraft: white for five ticks, itself for five, in
        /// step with its fuse. In its last half second it also swells.
        /// </summary>
        private void FlashTnt(ShownItem item)
        {
            if (item.fuse < 0f)
            {
                return;
            }

            // Minecraft only mentions the fuse when the TNT appears or moves; count it down in between.
            float fuse = Mathf.Max(0f, item.fuse - (Time.unscaledTime - item.fuseHeardAt) * 20f);
            bool flash = ((int)fuse / 5) % 2 == 0;
            MeshRenderer renderer = item.thing.GetComponent<MeshRenderer>();

            if (renderer != null && flash != item.flashing)
            {
                if (flash)
                {
                    if (flashMaterial == null)
                    {
                        Material basis = TexturedMaterial(0xFFFFFF);

                        if (basis == null)
                        {
                            return;
                        }

                        // The same name as the blocks' own, so the search for a Subnautica material to copy passes it over.
                        flashMaterial = new Material(basis);
                        flashMaterial.name = "MinecraftBlock";
                        flashMaterial.mainTexture = Texture2D.whiteTexture;
                        flashMaterial.color = Color.white;
                    }

                    item.ownMaterials = renderer.sharedMaterials;
                    Material[] white = new Material[item.ownMaterials.Length];

                    for (int i = 0; i < white.Length; i++)
                    {
                        white[i] = flashMaterial;
                    }

                    renderer.sharedMaterials = white;
                }
                else if (item.ownMaterials != null)
                {
                    renderer.sharedMaterials = item.ownMaterials;
                }

                item.flashing = flash;
            }

            float swell = fuse < 10f ? 1f + 0.3f * Mathf.Pow(1f - fuse / 10f, 3f) : 1f;
            item.thing.transform.localScale = Vector3.one * item.scale * swell;
        }

        // ---- Projectiles ----------------------------------------------------------------------

        /// <summary>
        /// "id kind style x y z vx vy vz": a Minecraft projectile is here (its middle), moving
        /// this far each Minecraft tick. It is drawn as its item, like a dropped item, but
        /// without the turning and bobbing: style 0 faces the camera (a snowball), style 1
        /// points along its flight (an arrow, a knife), style 2 stands upright at full size
        /// (lit TNT). "ITEMGONE id" removes it.
        /// </summary>
        private void HandleShot(string text)
        {
            string[] parts = text.Split(' ');
            int id, kind, style;
            float[] v = new float[6];

            // Lit TNT has a tenth number: the ticks left on its fuse.
            if ((parts.Length != 9 && parts.Length != 10)
                || !int.TryParse(parts[0], NumberStyles.Integer, CultureInfo.InvariantCulture, out id)
                || !int.TryParse(parts[1], NumberStyles.Integer, CultureInfo.InvariantCulture, out kind)
                || !int.TryParse(parts[2], NumberStyles.Integer, CultureInfo.InvariantCulture, out style))
            {
                return;
            }

            for (int i = 0; i < 6; i++)
            {
                if (!float.TryParse(parts[3 + i], NumberStyles.Float, CultureInfo.InvariantCulture, out v[i]))
                {
                    return;
                }
            }

            try
            {
                ShownItem item;

                if (!shownItems.TryGetValue(id, out item) || item.thing == null)
                {
                    if (itemRoot == null)
                    {
                        itemRoot = new GameObject("MinecraftItems");
                    }

                    item = new ShownItem();
                    item.kind = kind;
                    item.style = Mathf.Clamp(style, 0, 2);
                    item.thing = new GameObject("MinecraftProjectile");
                    item.thing.transform.SetParent(itemRoot.transform, false);
                    item.thing.AddComponent<MeshFilter>();
                    item.thing.AddComponent<MeshRenderer>();
                    DressItem(item);
                    ApplySubnauticaLighting(item.thing);
                    shownItems[id] = item;
                }

                // z runs the other way in Subnautica. Minecraft ticks 20 times a second.
                item.target = new Vector3(v[0], v[1], -v[2]);
                item.speed = new Vector3(v[3], v[4], -v[5]) * 20f;
                item.heardAt = Time.unscaledTime;

                float fuse;

                if (parts.Length == 10 && float.TryParse(parts[9], NumberStyles.Float, CultureInfo.InvariantCulture, out fuse))
                {
                    item.fuse = fuse;
                    item.fuseHeardAt = Time.unscaledTime;
                }

                if (item.speed.sqrMagnitude > 0.01f)
                {
                    item.heading = item.speed.normalized;
                }

                MoveShownShot(item);
            }
            catch (Exception e)
            {
                if (warnedMissing.Add("shot"))
                {
                    Logger.LogWarning("Could not show a Minecraft projectile: " + e);
                }
            }
        }

        /// <summary>Puts a shown projectile where it should be this frame.</summary>
        private void MoveShownShot(ShownItem item)
        {
            // Minecraft only says where it is 20 times a second. In between, carry it along at
            // its speed, but never further than a little over one tick's worth.
            float since = Mathf.Min(Time.unscaledTime - item.heardAt, 0.06f);
            Transform spot = item.thing.transform;
            spot.position = item.target + item.speed * since;

            if (item.style == 1)
            {
                // Item pictures of pointed things (arrows, swords, tridents) point to their top
                // right corner. Turn the model so that corner leads.
                spot.rotation = Quaternion.LookRotation(item.heading) * Quaternion.FromToRotation(new Vector3(1f, 1f, 0f).normalized, Vector3.forward);
            }
            else if (item.style == 2)
            {
                spot.rotation = Quaternion.identity;
            }
            else
            {
                Camera view = Camera.main;

                if (view != null)
                {
                    spot.rotation = view.transform.rotation;
                }
            }
        }

        /// <summary>
        /// "id n" then six numbers for each of n projectiles (where it is, and how far it is
        /// about to move this tick): Minecraft asks what each would hit. The reply is "RAYHIT id"
        /// then five numbers for each: how far along its line the hit is (0 to 1, or -1 for
        /// nothing), which way the surface faces, and 1 if it is a creature rather than scenery
        /// (2 for a creature too big for a grappling hook to drag).
        /// </summary>
        private void AnswerRays(string text, Player player)
        {
            string[] parts = text.Split(' ');
            int id, count;

            if (parts.Length < 2
                || !int.TryParse(parts[0], NumberStyles.Integer, CultureInfo.InvariantCulture, out id)
                || !int.TryParse(parts[1], NumberStyles.Integer, CultureInfo.InvariantCulture, out count)
                || count < 0 || parts.Length != 2 + count * 6)
            {
                return;
            }

            if (player != null)
            {
                RefreshSolidLayers(player);
            }

            StringBuilder answer = new StringBuilder("RAYHIT ");
            answer.Append(id.ToString(CultureInfo.InvariantCulture));
            float[] v = new float[6];

            for (int ray = 0; ray < count; ray++)
            {
                bool ok = true;

                for (int i = 0; i < 6; i++)
                {
                    ok &= float.TryParse(parts[2 + ray * 6 + i], NumberStyles.Float, CultureInfo.InvariantCulture, out v[i]);
                }

                Vector3 from = new Vector3(v[0], v[1], -v[2]);
                Vector3 step = new Vector3(v[3], v[4], -v[5]);
                float length = step.magnitude;
                float nearest = float.MaxValue;
                Vector3 normal = Vector3.zero;
                int creature = 0;

                if (ok && player != null && length > 0.0001f)
                {
                    Vector3 direction = step / length;

                    // Scenery: an exact, thin line, so a projectile stops right at the surface.
                    int hits = Physics.RaycastNonAlloc(from, direction, sweepHits, length, solidLayers, QueryTriggerInteraction.Ignore);

                    for (int i = 0; i < hits; i++)
                    {
                        RaycastHit hit = sweepHits[i];

                        if (hit.distance < nearest && IsScenery(hit.collider, player))
                        {
                            nearest = hit.distance;
                            normal = hit.normal;
                        }
                    }

                    // Creatures: a fatter line, so near misses on small fish still count.
                    hits = Physics.SphereCastNonAlloc(from, 0.25f, direction, sweepHits, length, ~0, QueryTriggerInteraction.Ignore);

                    for (int i = 0; i < hits; i++)
                    {
                        RaycastHit hit = sweepHits[i];

                        if (hit.collider == null || hit.collider.transform.IsChildOf(player.transform))
                        {
                            continue;
                        }

                        // Unity reports a zero position for something the line started inside of.
                        float distance = hit.point == Vector3.zero ? 0f : hit.distance;

                        if (distance >= nearest || hit.collider.GetComponentInParent<Creature>() == null)
                        {
                            continue;
                        }

                        LiveMixin life = hit.collider.GetComponentInParent<LiveMixin>();

                        if (life == null || life == player.liveMixin || life.health <= 0f)
                        {
                            continue;
                        }

                        nearest = distance;
                        normal = -direction;

                        // 2 for one too big for a grappling hook to drag in.
                        creature = IsBigCreature(life.gameObject) ? 2 : 1;
                    }
                }

                if (nearest == float.MaxValue)
                {
                    answer.Append(" -1 0 0 0 0");
                }
                else
                {
                    answer.Append(' ').Append((nearest / length).ToString("0.#####", CultureInfo.InvariantCulture))
                        .Append(' ').Append(normal.x.ToString("0.###", CultureInfo.InvariantCulture))
                        .Append(' ').Append(normal.y.ToString("0.###", CultureInfo.InvariantCulture))
                        .Append(' ').Append((-normal.z).ToString("0.###", CultureInfo.InvariantCulture))
                        .Append(' ').Append(creature.ToString(CultureInfo.InvariantCulture));
                }
            }

            Send(answer.ToString());
        }

        /// <summary>
        /// "x y z damage": a Minecraft projectile hit a creature at this point (Minecraft's
        /// coordinates). Take the damage off the nearest living creature there.
        /// </summary>
        private void HandleHurt(string numbers, Player player)
        {
            string[] parts = numbers.Split(' ');
            float x, y, z, damage;

            if (player == null || parts.Length != 4
                || !float.TryParse(parts[0], NumberStyles.Float, CultureInfo.InvariantCulture, out x)
                || !float.TryParse(parts[1], NumberStyles.Float, CultureInfo.InvariantCulture, out y)
                || !float.TryParse(parts[2], NumberStyles.Float, CultureInfo.InvariantCulture, out z)
                || !float.TryParse(parts[3], NumberStyles.Float, CultureInfo.InvariantCulture, out damage)
                || damage <= 0f)
            {
                return;
            }

            Vector3 point = new Vector3(x, y, -z);
            int count = Physics.OverlapSphereNonAlloc(point, 0.75f, overlapHits, ~0, QueryTriggerInteraction.Ignore);
            LiveMixin target = null;
            float nearest = float.MaxValue;

            for (int i = 0; i < count; i++)
            {
                Collider collider = overlapHits[i];

                if (collider == null || collider.transform.IsChildOf(player.transform) || collider.GetComponentInParent<Creature>() == null)
                {
                    continue;
                }

                LiveMixin life = collider.GetComponentInParent<LiveMixin>();

                if (life == null || life == player.liveMixin || life.health <= 0f)
                {
                    continue;
                }

                float distance = (collider.ClosestPointOnBounds(point) - point).sqrMagnitude;

                if (distance < nearest)
                {
                    nearest = distance;
                    target = life;
                }
            }

            if (target != null)
            {
                target.TakeDamage(damage, point, DamageType.Normal, player.gameObject);
            }
        }

        // ---- Light from blocks ------------------------------------------------------------------

        /// <summary>"id range strength RRGGBB flicker": blocks of this kind give off light.</summary>
        private void HandleLight(string text)
        {
            string[] parts = text.Split(' ');
            int id, colour, flicker;
            float range, strength;

            if (parts.Length != 5
                || !int.TryParse(parts[0], NumberStyles.Integer, CultureInfo.InvariantCulture, out id)
                || !float.TryParse(parts[1], NumberStyles.Float, CultureInfo.InvariantCulture, out range)
                || !float.TryParse(parts[2], NumberStyles.Float, CultureInfo.InvariantCulture, out strength)
                || !int.TryParse(parts[3], NumberStyles.HexNumber, CultureInfo.InvariantCulture, out colour)
                || !int.TryParse(parts[4], NumberStyles.Integer, CultureInfo.InvariantCulture, out flicker))
            {
                return;
            }

            blockLights[id] = new[]
            {
                Mathf.Clamp(range, 1f, 30f), Mathf.Clamp(strength, 0.1f, 3f),
                ((colour >> 16) & 0xFF) / 255f, ((colour >> 8) & 0xFF) / 255f, (colour & 0xFF) / 255f, flicker
            };
        }

        /// <summary>Gives a newly built block its light, if its kind has one.</summary>
        /// <summary>Minecraft's lights are made this much brighter here than Minecraft's own numbers say, and reach this much further.</summary>
        private const float LightBoost = 1.5f;
        private const float LightReachBoost = 1.2f;

        // ---- Light from what the player holds ---------------------------------------------------
        //
        // "HELDLIGHT range strength colour flicker" (as for a block's light) or "HELDLIGHT 0":
        // the thing in Minecraft's hand gives this light. One light is kept at the player's eyes.

        private Light heldLight;
        private float heldLightStrength;
        private bool heldLightFlickers;

        private void HandleHeldLight(string text)
        {
            string[] parts = text.Split(' ');
            int colour, flicker;
            float range, strength;

            if (parts.Length != 4
                || !float.TryParse(parts[0], NumberStyles.Float, CultureInfo.InvariantCulture, out range)
                || !float.TryParse(parts[1], NumberStyles.Float, CultureInfo.InvariantCulture, out strength)
                || !int.TryParse(parts[2], NumberStyles.HexNumber, CultureInfo.InvariantCulture, out colour)
                || !int.TryParse(parts[3], NumberStyles.Integer, CultureInfo.InvariantCulture, out flicker))
            {
                // "HELDLIGHT 0", or anything unreadable: no light.
                if (heldLight != null)
                {
                    heldLight.enabled = false;
                }

                heldLightStrength = 0f;
                return;
            }

            if (heldLight == null)
            {
                GameObject holder = new GameObject("MinecraftHeldLight");
                heldLight = holder.AddComponent<Light>();
                heldLight.type = LightType.Point;
                heldLight.shadows = LightShadows.None;
            }

            heldLight.range = Mathf.Clamp(range, 1f, 40f) * LightReachBoost;
            heldLightStrength = Mathf.Clamp(strength, 0.1f, 3f) * LightBoost;
            heldLight.intensity = heldLightStrength;
            heldLight.color = new Color(((colour >> 16) & 0xFF) / 255f, ((colour >> 8) & 0xFF) / 255f, (colour & 0xFF) / 255f, 1f);
            heldLightFlickers = flicker != 0;
            heldLight.enabled = true;
        }

        /// <summary>Every frame: keep the held light at the player's eyes, a little ahead, and make a flame flicker.</summary>
        private void TendHeldLight()
        {
            if (heldLight == null)
            {
                return;
            }

            bool on = linked && heldLightStrength > 0f;

            if (heldLight.enabled != on)
            {
                heldLight.enabled = on;
            }

            Camera view = Camera.main;

            if (!on || view == null)
            {
                return;
            }

            heldLight.transform.position = view.transform.position + view.transform.forward * 0.4f;
            heldLight.intensity = heldLightFlickers ? heldLightStrength * (0.82f + 0.3f * Mathf.PerlinNoise(Time.time * 6f, 7.3f)) : heldLightStrength;
        }

        // ---- Blocks that give light also glow ----------------------------------------------------

        private readonly Dictionary<int, Material> glowMaterials = new Dictionary<int, Material>();

        /// <summary>
        /// The material for a block that gives off light (a torch, fire, a sea lantern): the
        /// ordinary block material, set to shine with its own picture, so the block looks lit
        /// and not just the things around it. It uses the "glow" settings Subnautica's own
        /// glowing plants and lamps use.
        /// </summary>
        private Material GlowMaterial(int tint)
        {
            Material material;

            if (glowMaterials.TryGetValue(tint, out material) && material != null)
            {
                return material;
            }

            Material basis = TexturedMaterial(tint);

            // Until Subnautica's own see-through material has been found to copy, use the ordinary one.
            if (basis == null || cutoutTemplate == null)
            {
                return basis;
            }

            material = new Material(basis);
            material.name = "MinecraftBlock";
            material.EnableKeyword("MARMO_EMISSION");
            SetTextureIfPresent(material, "_Illum", blockAtlas != null ? (Texture)blockAtlas : Texture2D.whiteTexture);

            if (material.HasProperty("_GlowColor"))
            {
                material.SetColor("_GlowColor", material.color);
            }

            foreach (string setting in new[] { "_EnableGlow", "_GlowStrength", "_GlowStrengthNight", "_EmissionLM", "_EmissionLMNight" })
            {
                if (material.HasProperty(setting))
                {
                    material.SetFloat(setting, 1f);
                }
            }

            glowMaterials[tint] = material;
            return material;
        }

        private void AddLight(GameObject block, int id)
        {
            float[] kind;

            if (!blockLights.TryGetValue(id, out kind))
            {
                return;
            }

            try
            {
                // Its own object in the middle of the block, so it goes when the block goes.
                GameObject holder = new GameObject("MinecraftLight");
                holder.transform.SetParent(block.transform, false);
                holder.transform.localPosition = new Vector3(0.5f, 0.5f, -0.5f);

                Light light = holder.AddComponent<Light>();
                light.type = LightType.Point;
                light.range = kind[0] * LightReachBoost;
                light.intensity = kind[1] * LightBoost;
                light.color = new Color(kind[2], kind[3], kind[4], 1f);
                light.shadows = LightShadows.None;

                // Off until the next sort finds it is one of the nearest.
                light.enabled = false;

                BlockLight entry = new BlockLight();
                entry.light = light;
                entry.strength = kind[1] * LightBoost;
                entry.flicker = kind[5] > 0.5f;
                entry.seed = UnityEngine.Random.value * 100f;
                lights.Add(entry);
                nextLightSort = 0f;
            }
            catch (Exception e)
            {
                if (warnedMissing.Add("light"))
                {
                    Logger.LogWarning("Could not give a Minecraft block its light: " + e);
                }
            }
        }

        /// <summary>Every frame: make flames flicker, and a few times a second pick which lights are on.</summary>
        private void TendLights(Player player)
        {
            if (lights.Count == 0)
            {
                return;
            }

            if (Time.unscaledTime >= nextLightSort)
            {
                nextLightSort = Time.unscaledTime + 0.5f;

                // Forget the lights of blocks that have gone.
                lights.RemoveAll(entry => entry.light == null);

                Camera view = Camera.main;
                Vector3 eyes = view != null ? view.transform.position : (player != null ? player.transform.position : Vector3.zero);

                // Nearest first; the first few are switched on, the rest off.
                lights.Sort((a, b) => (a.light.transform.position - eyes).sqrMagnitude.CompareTo((b.light.transform.position - eyes).sqrMagnitude));

                for (int i = 0; i < lights.Count; i++)
                {
                    lights[i].light.enabled = i < MostLights;
                }
            }

            for (int i = 0; i < lights.Count && i < MostLights; i++)
            {
                BlockLight entry = lights[i];

                if (entry.flicker && entry.light != null)
                {
                    // A gentle, uneven wobble in brightness, different for each flame.
                    float wobble = Mathf.PerlinNoise(Time.time * 6f, entry.seed);
                    entry.light.intensity = entry.strength * (0.82f + 0.3f * wobble);
                }
            }
        }

        // ---- Effects ----------------------------------------------------------------------------

        /// <summary>Whether a spot is in the sea: below the surface, and not in the dry space the player is in.</summary>
        private bool IsUnderwater(Vector3 point)
        {
            if (point.y >= 0f)
            {
                return false;
            }

            Player player = Player.main;

            // Inside a base or the lifepod, things near the player are in the same air as them.
            return !(lastDry == 1 && player != null && (player.transform.position - point).sqrMagnitude < 144f);
        }

        /// <summary>
        /// "x y z id face": bits of the block of that kind at that position. Face -1 is the burst
        /// when it breaks; 0 to 5 (down, up, north, south, west, east) is one bit off the face
        /// being dug at.
        /// </summary>
        private void HandleDebris(string text)
        {
            string[] parts = text.Split(' ');
            int x, y, z, id, face;

            if (parts.Length != 5
                || !int.TryParse(parts[0], NumberStyles.Integer, CultureInfo.InvariantCulture, out x)
                || !int.TryParse(parts[1], NumberStyles.Integer, CultureInfo.InvariantCulture, out y)
                || !int.TryParse(parts[2], NumberStyles.Integer, CultureInfo.InvariantCulture, out z)
                || !int.TryParse(parts[3], NumberStyles.Integer, CultureInfo.InvariantCulture, out id)
                || !int.TryParse(parts[4], NumberStyles.Integer, CultureInfo.InvariantCulture, out face))
            {
                return;
            }

            BlockModel model;

            if (!blockModels.TryGetValue(id, out model) || model.quads.Length == 0 || atlasPixels == null || debris.Count > 60)
            {
                return;
            }

            try
            {
                if (model.pictures == null)
                {
                    model.pictures = PicturesOf(model.quads);
                }

                if (model.pictures.Count == 0)
                {
                    return;
                }

                // A lot of blocks breaking at once (an explosion): fewer bits each.
                if (Time.unscaledTime - lastBurstTime > 0.1f)
                {
                    burstsThisMoment = 0;
                }

                lastBurstTime = Time.unscaledTime;
                int bits = face >= 0 ? 1 : (++burstsThisMoment > 4 ? 12 : 48);

                // Bits are grouped by tint, since each tint is its own material.
                Dictionary<int, List<float[]>> byTint = new Dictionary<int, List<float[]>>();
                Vector3 corner = new Vector3(x, y, -z);

                for (int i = 0; i < bits; i++)
                {
                    // Which piece of which face's picture this bit shows: a quarter of it each
                    // way, like Minecraft. Bits that would be see-through are tried again.
                    float[] picture = null;
                    float u = 0f, v = 0f, du = 0f, dv = 0f;

                    for (int attempt = 0; attempt < 4; attempt++)
                    {
                        float[] candidate = model.pictures[UnityEngine.Random.Range(0, model.pictures.Count)];
                        du = (candidate[2] - candidate[0]) / 4f;
                        dv = (candidate[3] - candidate[1]) / 4f;
                        u = candidate[0] + du * UnityEngine.Random.Range(0, 4);
                        v = candidate[1] + dv * UnityEngine.Random.Range(0, 4);

                        if (AtlasOpacity(new Vector2(u + du / 2f, v + dv / 2f)) >= 26)
                        {
                            picture = candidate;
                            break;
                        }
                    }

                    if (picture == null)
                    {
                        continue;
                    }

                    // Where it starts (in the block, Minecraft's way round) and how fast it goes.
                    Vector3 inBlock, speed;

                    if (face < 0)
                    {
                        inBlock = new Vector3(UnityEngine.Random.value, UnityEngine.Random.value, UnityEngine.Random.value);
                        speed = (inBlock - new Vector3(0.5f, 0.5f, 0.5f)) * 3f + UnityEngine.Random.insideUnitSphere * 1.2f + Vector3.up * 1.2f;
                    }
                    else
                    {
                        Vector3 outward = face == 0 ? Vector3.down : face == 1 ? Vector3.up : face == 2 ? Vector3.back : face == 3 ? Vector3.forward : face == 4 ? Vector3.left : Vector3.right;
                        inBlock = new Vector3(UnityEngine.Random.value, UnityEngine.Random.value, UnityEngine.Random.value);

                        // Flatten onto the face, a little way outside it.
                        if (outward.x != 0f) inBlock.x = outward.x > 0f ? 1.1f : -0.1f;
                        if (outward.y != 0f) inBlock.y = outward.y > 0f ? 1.1f : -0.1f;
                        if (outward.z != 0f) inBlock.z = outward.z > 0f ? 1.1f : -0.1f;
                        speed = outward * 0.6f + UnityEngine.Random.insideUnitSphere * 0.5f;
                    }

                    // z runs the other way in Subnautica.
                    Vector3 place = corner + new Vector3(inBlock.x, inBlock.y, -inBlock.z);
                    speed.z = -speed.z;
                    float size = UnityEngine.Random.Range(0.05f, 0.1f) * (face < 0 ? 1f : 0.7f);
                    int tint = (int)picture[4];
                    List<float[]> list;

                    if (!byTint.TryGetValue(tint, out list))
                    {
                        list = new List<float[]>();
                        byTint[tint] = list;
                    }

                    list.Add(new[] { place.x, place.y, place.z, speed.x, speed.y, speed.z, size, u, v, du, dv });
                }

                foreach (KeyValuePair<int, List<float[]>> group in byTint)
                {
                    StartDebris(group.Value, group.Key, IsUnderwater(corner + new Vector3(0.5f, 0.5f, -0.5f)));
                }
            }
            catch (Exception e)
            {
                if (warnedMissing.Add("debris"))
                {
                    Logger.LogWarning("Could not show bits of a block: " + e);
                }
            }
        }

        /// <summary>Where each face of a model gets its picture from on the atlas: left, top, right, bottom, tint.</summary>
        private static List<float[]> PicturesOf(string quads)
        {
            List<float[]> pictures = new List<float[]>();

            foreach (string quad in quads.Split(';'))
            {
                string[] numbers = quad.Split(',');

                if (numbers.Length != 21)
                {
                    continue;
                }

                float u0 = float.MaxValue, v0 = float.MaxValue, u1 = float.MinValue, v1 = float.MinValue;

                for (int corner = 0; corner < 4; corner++)
                {
                    float u = float.Parse(numbers[4 + corner * 5], CultureInfo.InvariantCulture);
                    float v = float.Parse(numbers[5 + corner * 5], CultureInfo.InvariantCulture);
                    u0 = Mathf.Min(u0, u);
                    v0 = Mathf.Min(v0, v);
                    u1 = Mathf.Max(u1, u);
                    v1 = Mathf.Max(v1, v);
                }

                pictures.Add(new[] { u0, v0, u1, v1, (float)int.Parse(numbers[0], NumberStyles.HexNumber, CultureInfo.InvariantCulture) });
            }

            return pictures;
        }

        /// <summary>Sets a group of bits going. Each is: place (3), speed (3), size, and its piece of the atlas (4).</summary>
        private void StartDebris(List<float[]> bits, int tint, bool inWater)
        {
            Material material = TexturedMaterial(tint);

            if (material == null || bits.Count == 0)
            {
                return;
            }

            Debris burst = new Debris();
            int count = bits.Count;
            burst.places = new Vector3[count];
            burst.speeds = new Vector3[count];
            burst.sizes = new float[count];
            burst.corners = new Vector3[count * 4];
            Vector2[] spots = new Vector2[count * 4];
            Vector3[] facing = new Vector3[count * 4];
            int[] triangles = new int[count * 6];

            for (int i = 0; i < count; i++)
            {
                float[] bit = bits[i];
                burst.places[i] = new Vector3(bit[0], bit[1], bit[2]);
                burst.speeds[i] = new Vector3(bit[3], bit[4], bit[5]);
                burst.sizes[i] = bit[6];

                // Corners go: bottom left, top left, top right, bottom right.
                spots[i * 4] = new Vector2(bit[7], bit[8] + bit[10]);
                spots[i * 4 + 1] = new Vector2(bit[7], bit[8]);
                spots[i * 4 + 2] = new Vector2(bit[7] + bit[9], bit[8]);
                spots[i * 4 + 3] = new Vector2(bit[7] + bit[9], bit[8] + bit[10]);

                for (int corner = 0; corner < 4; corner++)
                {
                    // Lit as if facing up, whichever way the square is turned.
                    facing[i * 4 + corner] = Vector3.up;
                }

                triangles[i * 6] = i * 4;
                triangles[i * 6 + 1] = i * 4 + 1;
                triangles[i * 6 + 2] = i * 4 + 2;
                triangles[i * 6 + 3] = i * 4;
                triangles[i * 6 + 4] = i * 4 + 2;
                triangles[i * 6 + 5] = i * 4 + 3;
            }

            burst.mesh = new Mesh();
            burst.mesh.MarkDynamic();
            PlaceDebris(burst);
            burst.mesh.vertices = burst.corners;
            burst.mesh.uv = spots;
            burst.mesh.normals = facing;
            burst.mesh.triangles = triangles;

            burst.thing = new GameObject("MinecraftDebris");
            burst.thing.AddComponent<MeshFilter>().sharedMesh = burst.mesh;
            burst.thing.AddComponent<MeshRenderer>().sharedMaterial = material;
            ApplySubnauticaLighting(burst.thing);

            burst.born = Time.time;
            burst.life = UnityEngine.Random.Range(0.5f, 1.0f) * (inWater ? 1.6f : 1f);
            burst.inWater = inWater;
            debris.Add(burst);
        }

        /// <summary>Works out the four corners of every bit so that each square faces the camera.</summary>
        private void PlaceDebris(Debris burst)
        {
            Camera view = Camera.main;
            Vector3 right = view != null ? view.transform.right : Vector3.right;
            Vector3 up = view != null ? view.transform.up : Vector3.up;

            // They shrink away over the last part of their life.
            float left = burst.life > 0f ? 1f - (Time.time - burst.born) / burst.life : 1f;
            float shrink = Mathf.Clamp01(left * 3f);

            for (int i = 0; i < burst.places.Length; i++)
            {
                Vector3 r = right * (burst.sizes[i] * shrink);
                Vector3 u = up * (burst.sizes[i] * shrink);
                Vector3 at = burst.places[i];
                burst.corners[i * 4] = at - r - u;
                burst.corners[i * 4 + 1] = at - r + u;
                burst.corners[i * 4 + 2] = at + r + u;
                burst.corners[i * 4 + 3] = at + r - u;
            }
        }

        /// <summary>Every frame: let the bits fly and fall, and clear away the ones that have had their time.</summary>
        private void MoveDebris()
        {
            if (debris.Count == 0)
            {
                return;
            }

            float step = Mathf.Min(Time.deltaTime, 0.05f);

            for (int b = debris.Count - 1; b >= 0; b--)
            {
                Debris burst = debris[b];

                if (burst.thing == null || Time.time - burst.born > burst.life)
                {
                    if (burst.thing != null)
                    {
                        Destroy(burst.thing);
                    }

                    if (burst.mesh != null)
                    {
                        Destroy(burst.mesh);
                    }

                    debris.RemoveAt(b);
                    continue;
                }

                // In air they fall as in Minecraft. In water they are slowed hard and drift down.
                float fall = burst.inWater ? 2.5f : 16f;
                float keep = Mathf.Exp(-(burst.inWater ? 4f : 0.4f) * step);

                for (int i = 0; i < burst.places.Length; i++)
                {
                    Vector3 speed = burst.speeds[i];
                    speed.y -= fall * step;
                    speed *= keep;
                    burst.speeds[i] = speed;
                    burst.places[i] += speed * step;
                }

                PlaceDebris(burst);
                burst.mesh.vertices = burst.corners;
                burst.mesh.RecalculateBounds();
            }
        }

        /// <summary>"name x y z [nx ny nz]": Minecraft wants an effect at a spot (Minecraft's coordinates).</summary>
        private void HandleEffect(string text, Player player)
        {
            string[] parts = text.Split(' ');
            float[] v = new float[6];

            if (parts.Length != 4 && parts.Length != 7)
            {
                return;
            }

            for (int i = 1; i < parts.Length; i++)
            {
                if (!float.TryParse(parts[i], NumberStyles.Float, CultureInfo.InvariantCulture, out v[i - 1]))
                {
                    return;
                }
            }

            Vector3 normal = parts.Length == 7 ? new Vector3(v[3], v[4], -v[5]) : Vector3.up;

            if (normal.sqrMagnitude < 0.01f)
            {
                normal = Vector3.up;
            }

            PlayEffect(parts[0], new Vector3(v[0], v[1], -v[2]), normal.normalized, player);
        }

        /// <summary>
        /// Plays one of Subnautica's own effects for something that happened in Minecraft. Each is
        /// only used where it belongs: bubbles underwater, smoke in air; the warp swirl, the
        /// explosion, sparks and the dust or chips off a surface in either.
        /// </summary>
        private void PlayEffect(string name, Vector3 point, Vector3 normal, Player player)
        {
            if (player == null)
            {
                return;
            }

            try
            {
                FindEffects(player);
                bool underwater = IsUnderwater(point);

                // The same effect in nearly the same moment is one too many.
                float last;

                if (name != "impact" && effectLastPlayed.TryGetValue(name, out last) && Time.unscaledTime - last < 0.1f)
                {
                    return;
                }

                effectLastPlayed[name] = Time.unscaledTime;

                switch (name)
                {
                    case "impact":
                    case "land":
                        PlaySurfaceEffect(point, normal, VFXEventTypes.impact);
                        break;

                    case "spark":
                        if (VFXSurfaceTypeManager.main != null)
                        {
                            VFXSurfaceTypeManager.main.Play(VFXSurfaceTypes.metal, VFXEventTypes.knife, point);
                        }

                        break;

                    case "poof":
                        if (underwater)
                        {
                            PlayBubbles(point);
                        }
                        else
                        {
                            PlaySurfaceEffect(point, normal, VFXEventTypes.impact);
                        }

                        break;

                    case "smoke":
                        if (!underwater && smokeEffect != null)
                        {
                            ParticleSystem smoke = Instantiate(smokeEffect, point, Quaternion.LookRotation(Vector3.up));
                            smoke.gameObject.SetActive(true);
                            smoke.Play(true);
                            Destroy(smoke.gameObject, 3f);
                        }

                        break;

                    case "warp":
                    case "warpin":
                        // The Warper's swirl, where an ender pearl lands or an enderman arrives. In air as well as water.
                        if (warpEffect != null)
                        {
                            Destroy(Instantiate(warpEffect, point, Quaternion.identity), 6f);
                        }

                        break;

                    case "warpout":
                        // And the one it leaves behind, where an enderman was.
                        if (warpOutEffect != null || warpEffect != null)
                        {
                            Destroy(Instantiate(warpOutEffect != null ? warpOutEffect : warpEffect, point, Quaternion.identity), 6f);
                        }

                        break;

                    case "blast":
                        if (blastEffect != null)
                        {
                            PlayBlast(point);
                        }

                        break;
                }
            }
            catch (Exception e)
            {
                if (warnedMissing.Add("effect " + name))
                {
                    Logger.LogWarning("Could not play the \"" + name + "\" effect: " + e);
                }
            }
        }

        /// <summary>The puff Subnautica makes when something strikes a surface: sand, rock chips, sparks off metal.</summary>
        private void PlaySurfaceEffect(Vector3 point, Vector3 normal, VFXEventTypes kind)
        {
            if (VFXSurfaceTypeManager.main == null)
            {
                return;
            }

            // What is the surface made of? An object (a base wall, a wreck) says so itself;
            // for the seabed the game works it out from the ground at that spot.
            VFXSurfaceTypes surface = VFXSurfaceTypes.none;
            RaycastHit hit;

            if (Physics.Raycast(point + normal * 0.3f, -normal, out hit, 0.8f, solidLayers, QueryTriggerInteraction.Ignore) && hit.collider != null)
            {
                surface = Utils.GetObjectSurfaceType(hit.collider.gameObject, VFXSurfaceTypes.none);
            }

            if (surface == VFXSurfaceTypes.none)
            {
                surface = Utils.GetTerrainSurfaceType(point, normal, VFXSurfaceTypes.sand);
            }

            VFXSurfaceTypeManager.main.Play(surface, kind, point);
        }

        /// <summary>A burst of bubbles: the ones the player breathes out.</summary>
        private void PlayBubbles(Vector3 point)
        {
            if (bubblesEffect == null)
            {
                return;
            }

            ParticleSystem bubbles = Instantiate(bubblesEffect, point, Quaternion.LookRotation(Vector3.up));
            bubbles.gameObject.SetActive(true);
            bubbles.Play(true);
            Destroy(bubbles.gameObject, 3f);
        }

        /// <summary>
        /// The burst Subnautica shows when a Seamoth is destroyed, with its sound taken out
        /// (Minecraft plays its own explosion sound).
        /// </summary>
        private void PlayBlast(Vector3 point)
        {
            // Made switched off, so nothing in it starts (or makes a noise) before it has been changed.
            bool wasActive = blastEffect.activeSelf;
            blastEffect.SetActive(false);
            GameObject blast;

            try
            {
                blast = Instantiate(blastEffect, point, Quaternion.identity);
            }
            finally
            {
                blastEffect.SetActive(wasActive);
            }

            // The effect comes with the wreck of a Seamoth: broken hull pieces that are left lying
            // about. Only the burst itself is wanted, so everything solid is taken out: the
            // pieces' shapes (anything drawn that isn't a particle effect), what makes them
            // solid, and what makes them fall.
            foreach (LODGroup detail in blast.GetComponentsInChildren<LODGroup>(true))
            {
                DestroyImmediate(detail);
            }

            foreach (Renderer shape in blast.GetComponentsInChildren<Renderer>(true))
            {
                if (!(shape is ParticleSystemRenderer) && !(shape is TrailRenderer) && !(shape is LineRenderer))
                {
                    shape.enabled = false;
                    DestroyImmediate(shape);
                }
            }

            foreach (Collider solid in blast.GetComponentsInChildren<Collider>(true))
            {
                DestroyImmediate(solid);
            }

            foreach (Rigidbody body in blast.GetComponentsInChildren<Rigidbody>(true))
            {
                DestroyImmediate(body);
            }

            // Take out anything in it that makes sound: Minecraft plays its own explosion sound.
            foreach (Behaviour part in blast.GetComponentsInChildren<Behaviour>(true))
            {
                string kind = part != null ? part.GetType().Name : "";

                if (kind.StartsWith("FMOD", StringComparison.Ordinal) || kind.Contains("Audio") || kind.Contains("Sound"))
                {
                    part.enabled = false;
                    DestroyImmediate(part);
                }
            }

            blast.SetActive(true);

            // If anything in it would have outlived the burst, it goes with it.
            Destroy(blast, 6f);
        }

        private static void SetField(object target, string name, object value)
        {
            FieldInfo field = target.GetType().GetField(name, BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic);

            if (field != null)
            {
                field.SetValue(target, value);
            }
        }

        /// <summary>A splash where a dropped item, lit TNT or a projectile crosses the sea's surface.</summary>
        private void WatchForSplash(ShownItem item)
        {
            float height = item.thing.transform.position.y;
            float before = item.lastHeight;
            item.lastHeight = height;

            if (float.IsNaN(before) || (before >= 0f) == (height >= 0f) || splashEffect == null || lastDry == 1)
            {
                return;
            }

            Vector3 at = item.thing.transform.position;
            at.y = 0f;
            Destroy(Instantiate(splashEffect, at, Quaternion.identity), 4f);
        }

        /// <summary>
        /// Finds Subnautica's effects. Two are on the player (breath bubbles, the splash their
        /// arms make). The others belong to things that may not be around, so the game is asked
        /// to load those things' blueprints: the Seamoth (for its explosion and its smoke)
        /// and the Warper (for its swirl). Nothing is added to the world by this.
        /// </summary>
        private void FindEffects(Player player)
        {
            if (bubblesEffect == null)
            {
                PlayerBreathBubbles breath = player.GetComponentInChildren<PlayerBreathBubbles>(true);

                if (breath != null)
                {
                    bubblesEffect = breath.bubblesPrefab;
                }
            }

            if (splashEffect == null)
            {
                SwimWaterSplash arms = player.GetComponentInChildren<SwimWaterSplash>(true);

                if (arms != null)
                {
                    splashEffect = arms.swimSurfaceEffect;
                }
            }

            if (!effectsRequested)
            {
                effectsRequested = true;
                StartCoroutine(LoadBorrowedEffects());
            }
        }

        private IEnumerator LoadBorrowedEffects()
        {
            CoroutineTask<GameObject> seamoth = CraftData.GetPrefabForTechTypeAsync(TechType.Seamoth, false);
            yield return seamoth;

            try
            {
                GameObject blueprint = seamoth.GetResult();
                Vehicle vehicle = blueprint != null ? blueprint.GetComponent<Vehicle>() : null;

                // The burst the game shows when a Seamoth is destroyed.
                if (vehicle != null)
                {
                    blastEffect = vehicle.destructionEffect;
                }

                VFXSeamothDamages damage = blueprint != null ? blueprint.GetComponentInChildren<VFXSeamothDamages>(true) : null;

                if (damage != null)
                {
                    smokeEffect = damage.smokeParticles;
                }
            }
            catch (Exception e)
            {
                Logger.LogWarning("Could not borrow the Seamoth's effects: " + e);
            }

            CoroutineTask<GameObject> warper = CraftData.GetPrefabForTechTypeAsync(TechType.Warper, false);
            yield return warper;

            try
            {
                GameObject blueprint = warper.GetResult();
                Warper creature = blueprint != null ? blueprint.GetComponent<Warper>() : null;

                if (creature != null)
                {
                    warpEffect = creature.warpInEffectPrefab;
                    warpOutEffect = creature.warpOutEffectPrefab;
                }
            }
            catch (Exception e)
            {
                Logger.LogWarning("Could not borrow the Warper's effect: " + e);
            }

            Logger.LogInfo("Subnautica effects found: bubbles " + (bubblesEffect != null) + ", splash " + (splashEffect != null)
                + ", explosion " + (blastEffect != null ? blastEffect.name : "no") + ", smoke " + (smokeEffect != null)
                + ", warp " + (warpEffect != null) + ", surface effects " + (VFXSurfaceTypeManager.main != null));
        }

        // ---- Attacking ------------------------------------------------------------------------

        /// <summary>
        /// "ATTACK damage reach": Minecraft's player swung. Look along the camera for the first
        /// thing within reach; if it is alive (a creature, or anything else with health), take
        /// the damage off it and tell Minecraft the hit landed. Scenery in the way blocks the hit.
        /// </summary>
        private void HandleAttack(string numbers, Player player)
        {
            string[] parts = numbers.Split(' ');
            float damage, reach;

            if ((parts.Length != 2 && parts.Length != 4)
                || !float.TryParse(parts[0], NumberStyles.Float, CultureInfo.InvariantCulture, out damage)
                || !float.TryParse(parts[1], NumberStyles.Float, CultureInfo.InvariantCulture, out reach)
                || damage <= 0f)
            {
                return;
            }

            // From enchantments on the weapon: seconds of burning (Fire Aspect) and levels of shove (Knockback).
            int burnSeconds = 0;
            int knockback = 0;

            if (parts.Length == 4)
            {
                int.TryParse(parts[2], NumberStyles.Integer, CultureInfo.InvariantCulture, out burnSeconds);
                int.TryParse(parts[3], NumberStyles.Integer, CultureInfo.InvariantCulture, out knockback);
            }

            Camera view = Camera.main;

            if (view == null)
            {
                return;
            }

            // A thin "fat ray" (a ball pushed along the line of sight) so that near misses on
            // small creatures still count, as they do with Subnautica's own knife.
            Vector3 from = view.transform.position;
            Vector3 direction = view.transform.forward;
            int count = Physics.SphereCastNonAlloc(from, 0.2f, direction, sweepHits, Mathf.Clamp(reach, 1f, 8f), ~0, QueryTriggerInteraction.Ignore);

            float nearest = float.MaxValue;
            RaycastHit best = default(RaycastHit);
            bool found = false;

            for (int i = 0; i < count; i++)
            {
                RaycastHit hit = sweepHits[i];

                if (hit.collider == null || hit.collider.transform.IsChildOf(player.transform))
                {
                    continue;
                }

                if (hit.distance < nearest)
                {
                    nearest = hit.distance;
                    best = hit;
                    found = true;
                }
            }

            if (!found)
            {
                return;
            }

            LiveMixin target = best.collider.GetComponentInParent<LiveMixin>();

            if (target == null || target == player.liveMixin || target.health <= 0f)
            {
                return;
            }

            // Vehicles, the Cyclops and bases aren't for punching: a swing aboard one (or at
            // a parked one) would otherwise wear its hull down from the inside.
            if (target.GetComponentInParent<Vehicle>() != null || target.GetComponentInParent<SubRoot>() != null)
            {
                return;
            }

            // Unity reports a zero position for something the ball started inside of.
            Vector3 hitPoint = best.point == Vector3.zero ? from + direction * 0.5f : best.point;
            target.TakeDamage(damage, hitPoint, DamageType.Normal, player.gameObject);
            Send("HIT");

            if (target == null || target.health <= 0f)
            {
                return;
            }

            if (burnSeconds > 0)
            {
                // Minecraft's fire does one point a second; five here, like all damage that crosses over.
                burning[target] = Time.time + Mathf.Min(burnSeconds, 30);
            }

            if (knockback > 0)
            {
                Rigidbody body = target.GetComponentInParent<Rigidbody>();

                if (body != null && !body.isKinematic)
                {
                    // A shove along the swing. Heavy creatures (anything over about a stalker's
                    // weight) move less, and a leviathan barely notices.
                    float weight = Mathf.Min(1f, 150f / Mathf.Max(1f, body.mass));
                    body.AddForce(direction * (4f * Mathf.Min(knockback, 3) * weight), ForceMode.VelocityChange);
                }
            }
        }

        /// <summary>Creatures set alight by Fire Aspect, and when each stops burning.</summary>
        private readonly Dictionary<LiveMixin, float> burning = new Dictionary<LiveMixin, float>();
        private readonly List<LiveMixin> burnScratch = new List<LiveMixin>();
        private float nextBurn;

        /// <summary>Once a second: every burning creature takes a point of Minecraft's fire damage (five here).</summary>
        private void TendBurning(Player player)
        {
            if (burning.Count == 0 || Time.time < nextBurn)
            {
                return;
            }

            nextBurn = Time.time + 1f;
            burnScratch.Clear();

            foreach (KeyValuePair<LiveMixin, float> entry in burning)
            {
                LiveMixin life = entry.Key;

                if (life == null || life.health <= 0f || Time.time > entry.Value)
                {
                    burnScratch.Add(life);
                    continue;
                }

                life.TakeDamage(5f, life.transform.position, DamageType.Fire, player.gameObject);
            }

            foreach (LiveMixin done in burnScratch)
            {
                burning.Remove(done);
            }
        }

        // ---- Kills --------------------------------------------------------------------------------
        //
        // When a creature dies to damage this player dealt, Minecraft is told what it was and
        // where ("KILLED name x y z"), and drops Minecraft items there. The two small methods
        // below are slotted in around Subnautica's own damage code: one notes the creature's
        // health before the damage, the other looks at it after.

        private static readonly ConcurrentQueue<string> killNotes = new ConcurrentQueue<string>();

        public static void NoteHealthBefore(LiveMixin __instance, out float __state)
        {
            __state = __instance != null ? __instance.health : 0f;
        }

        public static void NoteKill(LiveMixin __instance, float __state, GameObject dealer)
        {
            try
            {
                Player player = Player.main;

                if (!linkedNow || __state <= 0f || __instance == null || __instance.health > 0f || player == null
                    || dealer != player.gameObject || __instance == player.liveMixin || __instance.GetComponentInParent<Creature>() == null)
                {
                    return;
                }

                Vector3 at = __instance.transform.position;
                killNotes.Enqueue(string.Format(CultureInfo.InvariantCulture, "KILLED {0} {1:0.##} {2:0.##} {3:0.##}", CraftData.GetTechType(__instance.gameObject), at.x, at.y, at.z));
            }
            catch (Exception)
            {
                // Not being able to tell just means no drops for this one.
            }
        }

        // ---- The players' Minecraft characters ------------------------------------------------

        /// <summary>"n w h pixels": picture number n (a skin, a suit of armour), for drawing players with.</summary>
        private void HandleSkin(string text)
        {
            string[] parts = text.Split(new[] { ' ' }, 4);
            int id, width, height;

            if (parts.Length != 4
                || !int.TryParse(parts[0], NumberStyles.Integer, CultureInfo.InvariantCulture, out id)
                || !int.TryParse(parts[1], NumberStyles.Integer, CultureInfo.InvariantCulture, out width)
                || !int.TryParse(parts[2], NumberStyles.Integer, CultureInfo.InvariantCulture, out height)
                || width <= 0 || height <= 0)
            {
                return;
            }

            try
            {
                byte[] pixels = Convert.FromBase64String(parts[3]);

                if (pixels.Length != width * height * 4)
                {
                    return;
                }

                SkinPicture old;

                if (skins.TryGetValue(id, out old) && old.picture != null)
                {
                    // The same picture again with more on it (a sheet of letters Minecraft has
                    // added to): the pixels are changed where they are, so everything already
                    // drawn with the picture carries on being drawn with it.
                    if (old.width == width && old.height == height)
                    {
                        old.picture.LoadRawTextureData(pixels);
                        old.picture.Apply(false);
                        old.pixels = pixels;
                        return;
                    }

                    Destroy(old.picture);
                }

                Texture2D picture = new Texture2D(width, height, TextureFormat.RGBA32, false);

                // "Point" keeps Minecraft's pixels sharp instead of smoothing them together.
                picture.filterMode = FilterMode.Point;
                picture.wrapMode = TextureWrapMode.Clamp;
                picture.LoadRawTextureData(pixels);
                picture.Apply(false);
                skins[id] = new SkinPicture { picture = picture, pixels = pixels, width = width, height = height };
                Logger.LogInfo("Received player picture " + id + ", " + width + " x " + height);
            }
            catch (Exception e)
            {
                if (warnedMissing.Add("skin"))
                {
                    Logger.LogWarning("Could not read a player picture from Minecraft: " + e.Message);
                }
            }
        }

        /// <summary>"key faces": the lasting part of one look. See AvatarShape.</summary>
        private void HandleAvatarShape(string text)
        {
            int space = text.IndexOf(' ');

            if (space <= 0)
            {
                return;
            }

            try
            {
                byte[] raw = Convert.FromBase64String(text.Substring(space + 1));
                AvatarShape shape = new AvatarShape();
                shape.raw = raw;
                shape.faces = raw.Length / AvatarFaceBytes;
                FillAvatarShape(shape);
                avatarShapes[text.Substring(0, space)] = shape;
            }
            catch (Exception e)
            {
                if (warnedMissing.Add("avatar shape"))
                {
                    Logger.LogWarning("Could not read a player's shape from Minecraft: " + e);
                }
            }
        }

        /// <summary>
        /// Works out the recipe for a look: cuts each face along the pixels of its picture and
        /// notes, for every point of what is kept, which face it is on and how far across and
        /// along. Done once when the look arrives, and again if the block atlas arrives later.
        /// </summary>
        private void FillAvatarShape(AvatarShape shape)
        {
            byte[] raw = shape.raw;
            List<long> groups = new List<long>();
            List<List<int>> triangles = new List<List<int>>();
            List<int> recipeFace = new List<int>();
            List<float> recipeAcross = new List<float>();
            List<float> recipeAlong = new List<float>();
            List<Vector2> spots = new List<Vector2>();
            Vector2[] t = new Vector2[4];

            shape.usesAtlas = false;
            shape.cutWithAtlas = atlasPixels != null;

            for (int face = 0; face < shape.faces; face++)
            {
                int at = face * AvatarFaceBytes;
                int picture = BitConverter.ToUInt16(raw, at);
                int tint = (raw[at + 2] << 16) | (raw[at + 3] << 8) | raw[at + 4];

                for (int corner = 0; corner < 4; corner++)
                {
                    t[corner] = new Vector2(BitConverter.ToSingle(raw, at + 6 + corner * 8), BitConverter.ToSingle(raw, at + 10 + corner * 8));
                }

                // The pixels of this face's picture, if they are to hand.
                byte[] pixels = null;
                int width = 0;
                int height = 0;

                if (picture == 0)
                {
                    shape.usesAtlas = true;
                    pixels = atlasPixels;
                    width = atlasWidth;
                    height = atlasHeight;
                }
                else
                {
                    SkinPicture skin;

                    if (skins.TryGetValue(picture, out skin))
                    {
                        pixels = skin.pixels;
                        width = skin.width;
                        height = skin.height;
                    }
                }

                if (pixels != null && pixels.Length < width * height * 4)
                {
                    pixels = null;
                }

                // Faces are grouped by picture and tint, because each pairing needs its own material.
                long key = ((long)picture << 24) | (long)tint;
                int group = groups.IndexOf(key);

                if (group < 0)
                {
                    group = groups.Count;
                    groups.Add(key);
                    triangles.Add(new List<int>());
                }

                // How many pixels of the picture the face covers along each of its sides.
                int across = 1;
                int along = 1;

                if (pixels != null && recipeFace.Count < 60000)
                {
                    across = PixelsAcross(t[0], t[1], width, height);
                    along = PixelsAcross(t[1], t[2], width, height);
                }

                bool[] solid = new bool[across * along];
                int solidCount = 0;

                for (int i = 0; i < across; i++)
                {
                    for (int j = 0; j < along; j++)
                    {
                        bool isSolid = pixels == null || OpacityAt(pixels, width, height, Blend(t, (i + 0.5f) / across, (j + 0.5f) / along)) >= 26;
                        solid[i * along + j] = isSolid;

                        if (isSolid)
                        {
                            solidCount++;
                        }
                    }
                }

                if (solidCount == 0)
                {
                    continue;
                }

                // No see-through pixels (most faces): keep the face whole.
                if (solidCount == solid.Length)
                {
                    AddAvatarPiece(face, t, 0f, 1f, 0f, 1f, 0f, 0f, recipeFace, recipeAcross, recipeAlong, spots, triangles[group]);
                    continue;
                }

                // Otherwise one strip for every unbroken run of solid pixels in each row.
                for (int i = 0; i < across; i++)
                {
                    int j = 0;

                    while (j < along)
                    {
                        if (!solid[i * along + j])
                        {
                            j++;
                            continue;
                        }

                        int runStart = j;

                        while (j < along && solid[i * along + j])
                        {
                            j++;
                        }

                        // The strip's place on the picture is pulled in a touch from its edges,
                        // so it can't pick up the colour of the pixel next door.
                        AddAvatarPiece(face, t, (float)i / across, (float)(i + 1) / across, (float)runStart / along, (float)j / along,
                            0.02f / across, 0.02f / along, recipeFace, recipeAcross, recipeAlong, spots, triangles[group]);
                    }
                }
            }

            shape.recipeFace = recipeFace.ToArray();
            shape.recipeAcross = recipeAcross.ToArray();
            shape.recipeAlong = recipeAlong.ToArray();
            shape.spots = spots.ToArray();
            shape.triangles = new int[groups.Count][];
            shape.pictures = new int[groups.Count];
            shape.tints = new int[groups.Count];

            for (int i = 0; i < groups.Count; i++)
            {
                shape.triangles[i] = triangles[i].ToArray();
                shape.pictures[i] = (int)(groups[i] >> 24);
                shape.tints[i] = (int)(groups[i] & 0xFFFFFF);
            }

            shape.version++;
        }

        /// <summary>Adds one four-cornered piece of a face to a recipe: from s0 to s1 of the way across it, r0 to r1 of the way along.</summary>
        private static void AddAvatarPiece(int face, Vector2[] t, float s0, float s1, float r0, float r1, float sIn, float rIn,
            List<int> recipeFace, List<float> recipeAcross, List<float> recipeAlong, List<Vector2> spots, List<int> triangles)
        {
            int first = recipeFace.Count;

            for (int corner = 0; corner < 4; corner++)
            {
                bool far = corner == 1 || corner == 2;
                bool high = corner >= 2;
                recipeFace.Add(face);
                recipeAcross.Add(far ? s1 : s0);
                recipeAlong.Add(high ? r1 : r0);
                spots.Add(Blend(t, far ? s1 - sIn : s0 + sIn, high ? r1 - rIn : r0 + rIn));
            }

            // Two triangles, going round the other way from Minecraft, as for blocks (see BuildMesh).
            triangles.Add(first);
            triangles.Add(first + 2);
            triangles.Add(first + 1);
            triangles.Add(first);
            triangles.Add(first + 3);
            triangles.Add(first + 2);
        }

        /// <summary>How many pixels of a picture lie between two places on it, at least 1.</summary>
        private static int PixelsAcross(Vector2 from, Vector2 to, int width, int height)
        {
            float pixels = Mathf.Max(Mathf.Abs(to.x - from.x) * width, Mathf.Abs(to.y - from.y) * height);
            return Mathf.Clamp(Mathf.RoundToInt(pixels), 1, MostPiecesPerSide);
        }

        /// <summary>How solid a picture is at a place on it: 0 for see-through to 255 for solid.</summary>
        private static int OpacityAt(byte[] pixels, int width, int height, Vector2 spot)
        {
            int x = Mathf.Clamp((int)(spot.x * width), 0, width - 1);
            int y = Mathf.Clamp((int)(spot.y * height), 0, height - 1);
            return pixels[(y * width + x) * 4 + 3];
        }

        /// <summary>"id key x y z flags corners": where a player is and how they are posed just now.</summary>
        private void HandleAvatar(string text)
        {
            string[] parts = text.Split(new[] { ' ' }, 7);
            int id, flags;
            float x, y, z;
            AvatarShape shape;

            if (parts.Length != 7
                || !int.TryParse(parts[0], NumberStyles.Integer, CultureInfo.InvariantCulture, out id)
                || !float.TryParse(parts[2], NumberStyles.Float, CultureInfo.InvariantCulture, out x)
                || !float.TryParse(parts[3], NumberStyles.Float, CultureInfo.InvariantCulture, out y)
                || !float.TryParse(parts[4], NumberStyles.Float, CultureInfo.InvariantCulture, out z)
                || !int.TryParse(parts[5], NumberStyles.Integer, CultureInfo.InvariantCulture, out flags)
                || !avatarShapes.TryGetValue(parts[1], out shape))
            {
                return;
            }

            try
            {
                byte[] data = Convert.FromBase64String(parts[6]);
                int corners = shape.faces * 4;

                if (data.Length != corners * 6)
                {
                    return;
                }

                // z runs the other way in Subnautica.
                Vector3 place = new Vector3(x, y, -z);
                Avatar avatar;

                if (!avatars.TryGetValue(id, out avatar) || avatar.thing == null)
                {
                    if (avatarRoot == null)
                    {
                        avatarRoot = new GameObject("MinecraftPlayers");
                    }

                    avatar = new Avatar();
                    avatar.thing = new GameObject("MinecraftPlayer");
                    avatar.thing.transform.SetParent(avatarRoot.transform, false);
                    avatar.thing.transform.position = place;
                    avatar.mesh = new Mesh();

                    // Tells Unity this shape changes all the time, so it keeps it somewhere quick to change.
                    avatar.mesh.MarkDynamic();
                    avatar.thing.AddComponent<MeshFilter>().sharedMesh = avatar.mesh;
                    avatar.renderer = avatar.thing.AddComponent<MeshRenderer>();
                    ApplySubnauticaLighting(avatar.thing);
                    avatar.shownPlace = place;
                    avatars[id] = avatar;
                    // (Only for players: mobs, chests and signs are drawn the same way, and there are a great many of those.)
                    if ((flags & 4) == 0)
                    {
                        Logger.LogInfo("Drawing a Minecraft player (number " + id + ") with " + shape.faces + " faces");
                    }
                }

                bool fresh = avatar.shape != shape || avatar.target == null || avatar.target.Length != corners;

                if (fresh)
                {
                    avatar.target = new Vector3[corners];
                }

                bool moved = fresh;

                // 128: a beacon's beam, cut off at the furthest Minecraft can send. Any corner
                // at that height is really far up out of sight, and is put there.
                bool beam = (flags & 128) != 0;

                for (int i = 0; i < corners; i++)
                {
                    int at = i * 6;
                    Vector3 corner = new Vector3(
                        BitConverter.ToInt16(data, at) / AvatarUnits,
                        BitConverter.ToInt16(data, at + 2) / AvatarUnits,
                        -BitConverter.ToInt16(data, at + 4) / AvatarUnits);

                    if (beam && corner.y > 59f)
                    {
                        corner.y = 1024f;
                    }

                    moved |= (corner - avatar.target[i]).sqrMagnitude > 1e-7f;
                    avatar.target[i] = corner;
                }

                if (moved)
                {
                    avatar.busyUntil = Time.unscaledTime + 0.6f;
                }

                // 64: it moves evenly (see Avatar.even). Note where it is drawn just now, to move on from.
                avatar.even = (flags & 64) != 0;

                if (fresh || !avatar.even || avatar.shown == null || avatar.shown.Length != corners)
                {
                    avatar.from = null;
                }
                else if (moved)
                {
                    if (avatar.from == null || avatar.from.Length != corners)
                    {
                        avatar.from = new Vector3[corners];
                    }

                    Array.Copy(avatar.shown, avatar.from, corners);
                    avatar.movedAt = Time.unscaledTime;
                }

                avatar.place = place;
                avatar.heardAt = Time.unscaledTime;
                avatar.hurt = (flags & 1) != 0;
                avatar.self = (flags & 2) != 0;

                // The boat this player is sitting in: where it is, measured from where
                // Minecraft has just said the player's own feet are.
                avatar.ownRide = (flags & 32) != 0;

                if (avatar.ownRide)
                {
                    avatar.rideOffset = place - (target - Vector3.up * targetEyeHeight);
                }
                avatar.mob = (flags & 4) != 0;
                avatar.white = (flags & 8) != 0;
                avatar.riding = (flags & 16) != 0;

                if (fresh)
                {
                    // A different look (something new in the hand, say): no gliding from the old one.
                    avatar.shape = shape;
                    avatar.shown = (Vector3[])avatar.target.Clone();
                    avatar.dressedVersion = -1;
                }
            }
            catch (Exception e)
            {
                if (warnedMissing.Add("avatar"))
                {
                    Logger.LogWarning("Could not show a Minecraft player: " + e);
                }
            }
        }

        /// <summary>"id": that player is no longer there to draw.</summary>
        private void HandleAvatarGone(string text)
        {
            int id;
            Avatar avatar;

            if (int.TryParse(text.Trim(), NumberStyles.Integer, CultureInfo.InvariantCulture, out id) && avatars.TryGetValue(id, out avatar))
            {
                RemoveAvatar(avatar);
                avatars.Remove(id);
            }
        }

        private static void RemoveAvatar(Avatar avatar)
        {
            if (avatar.thing != null)
            {
                Destroy(avatar.thing);
            }

            if (avatar.mesh != null)
            {
                Destroy(avatar.mesh);
            }
        }

        /// <summary>Every frame: glide each player towards where Minecraft last said they were, limb by limb.</summary>
        private void MoveAvatars()
        {
            if (avatars.Count == 0)
            {
                return;
            }

            float now = Time.unscaledTime;
            float glide = 1f - Mathf.Exp(-FollowSharpness * Time.unscaledDeltaTime);
            float limbGlide = 1f - Mathf.Exp(-30f * Time.unscaledDeltaTime);
            bool templateNow = cutoutTemplate != null;
            avatarScratch.Clear();

            foreach (KeyValuePair<int, Avatar> entry in avatars)
            {
                Avatar avatar = entry.Value;

                // Minecraft stopped mentioning them (it left, or the link dropped mid-sentence).
                if (avatar.thing == null || now - avatar.heardAt > 1.5f)
                {
                    avatarScratch.Add(entry.Key);
                    continue;
                }

                AvatarShape shape = avatar.shape;

                if (shape == null)
                {
                    continue;
                }

                try
                {
                    // The atlas arrived after this look did: cut its held items out properly now.
                    if (shape.usesAtlas && !shape.cutWithAtlas && atlasPixels != null)
                    {
                        FillAvatarShape(shape);
                    }

                    if (avatar.dressedVersion != shape.version)
                    {
                        // A new look, or the same one cut afresh: the shape has to be worked out again.
                        avatar.busyUntil = now + 0.6f;
                        avatar.workedOut = false;
                    }

                    if (avatar.dressedVersion != shape.version || avatar.dressedHurt != avatar.hurt || avatar.dressedWhite != avatar.white || avatar.dressedWithTemplate != templateNow)
                    {
                        DressAvatar(avatar, templateNow);
                    }

                    // Your own character is only drawn while you are looking at it from outside.
                    bool visible = !avatar.self || outsideCamera != null;

                    if (avatar.renderer.enabled != visible)
                    {
                        avatar.renderer.enabled = visible;
                    }

                    if (avatar.seated)
                    {
                        // In a vehicle: the place is the seat's (see SeatRiders); only the limbs glide.
                        for (int i = 0; i < avatar.shown.Length; i++)
                        {
                            avatar.shown[i] = Vector3.Lerp(avatar.shown[i], avatar.target[i], limbGlide);
                        }
                    }
                    else if ((avatar.self || avatar.ownRide) && hasPlaced)
                    {
                        // Your own character (and a boat you are in) goes exactly where your eyes
                        // are being drawn, so it stays rock steady however fast you move.
                        avatar.shownPlace = lastEyes - Vector3.up * targetEyeHeight + (avatar.ownRide ? avatar.rideOffset : Vector3.zero);

                        for (int i = 0; i < avatar.shown.Length; i++)
                        {
                            avatar.shown[i] = Vector3.Lerp(avatar.shown[i], avatar.target[i], limbGlide);
                        }
                    }
                    else if ((avatar.place - avatar.shownPlace).sqrMagnitude > SnapDistance * SnapDistance)
                    {
                        // A big jump (a teleport) is made at once.
                        avatar.shownPlace = avatar.place;
                        Array.Copy(avatar.target, avatar.shown, avatar.target.Length);
                    }
                    else if (avatar.even && avatar.from != null && avatar.from.Length == avatar.shown.Length)
                    {
                        // At an even pace, to arrive one Minecraft tick (a twentieth of a second) after the pose did.
                        avatar.shownPlace = Vector3.Lerp(avatar.shownPlace, avatar.place, glide);
                        float along = Mathf.Clamp01((now - avatar.movedAt) / 0.05f);

                        for (int i = 0; i < avatar.shown.Length; i++)
                        {
                            avatar.shown[i] = Vector3.Lerp(avatar.from[i], avatar.target[i], along);
                        }
                    }
                    else
                    {
                        avatar.shownPlace = Vector3.Lerp(avatar.shownPlace, avatar.place, glide);

                        for (int i = 0; i < avatar.shown.Length; i++)
                        {
                            avatar.shown[i] = Vector3.Lerp(avatar.shown[i], avatar.target[i], limbGlide);
                        }
                    }

                    avatar.thing.transform.position = avatar.shownPlace;

                    if (!visible)
                    {
                        continue;
                    }

                    // At rest, and already drawn as it stands: nothing to work out.
                    if (avatar.mob && avatar.workedOut && now > avatar.busyUntil)
                    {
                        continue;
                    }

                    avatar.workedOut = true;
                    WorkOutAvatar(avatar);
                    avatar.mesh.vertices = avatar.points;
                    avatar.mesh.RecalculateNormals();
                    avatar.mesh.RecalculateBounds();
                }
                catch (Exception e)
                {
                    if (warnedMissing.Add("avatar move"))
                    {
                        Logger.LogWarning("Could not move a Minecraft player: " + e);
                    }
                }
            }

            foreach (int id in avatarScratch)
            {
                RemoveAvatar(avatars[id]);
                avatars.Remove(id);
            }
        }

        /// <summary>Works out every point of a player's shape from the recipe and where the corners are just now.</summary>
        private static void WorkOutAvatar(Avatar avatar)
        {
            AvatarShape shape = avatar.shape;
            Vector3[] c = avatar.shown;
            Vector3[] points = avatar.points;

            for (int i = 0; i < points.Length; i++)
            {
                int at = shape.recipeFace[i] * 4;
                float s = shape.recipeAcross[i];
                float r = shape.recipeAlong[i];
                Vector3 low = c[at] + (c[at + 1] - c[at]) * s;
                Vector3 high = c[at + 3] + (c[at + 2] - c[at + 3]) * s;
                points[i] = low + (high - low) * r;
            }
        }

        /// <summary>Gives a player's object the shape and materials for its current look.</summary>
        private void DressAvatar(Avatar avatar, bool templateNow)
        {
            AvatarShape shape = avatar.shape;
            avatar.points = new Vector3[shape.recipeFace.Length];
            WorkOutAvatar(avatar);

            Mesh mesh = avatar.mesh;
            mesh.Clear();
            mesh.indexFormat = avatar.points.Length > 65000 ? UnityEngine.Rendering.IndexFormat.UInt32 : UnityEngine.Rendering.IndexFormat.UInt16;
            mesh.vertices = avatar.points;
            mesh.uv = shape.spots;
            mesh.subMeshCount = shape.triangles.Length;

            for (int i = 0; i < shape.triangles.Length; i++)
            {
                mesh.SetTriangles(shape.triangles[i], i);
            }

            mesh.RecalculateNormals();
            mesh.RecalculateBounds();

            Material[] materials = new Material[shape.pictures.Length];

            for (int i = 0; i < materials.Length; i++)
            {
                materials[i] = avatar.white ? WhiteMaterial() : AvatarMaterial(shape.pictures[i], shape.tints[i], avatar.hurt);
            }

            avatar.dressedWhite = avatar.white;

            avatar.renderer.sharedMaterials = materials;
            avatar.dressedVersion = shape.version;
            avatar.dressedHurt = avatar.hurt;
            avatar.dressedWithTemplate = templateNow;
        }

        /// <summary>
        /// The material for the parts of a player that show one picture with one tint. It is a
        /// copy of the material blocks use, showing that picture instead of the block atlas.
        /// A player who was just hurt gets a reddened one, as in Minecraft.
        /// </summary>
        private Material AvatarMaterial(int picture, int tint, bool hurt)
        {
            // Materials made before Subnautica's see-through material was found are made again from it.
            if (cutoutTemplate != null && !avatarMaterialsFromTemplate)
            {
                avatarMaterials.Clear();
                avatarMaterialsFromTemplate = true;
            }

            // Held items, unhurt: exactly what dropped items use.
            if (picture == 0 && !hurt)
            {
                return TexturedMaterial(tint);
            }

            long key = ((long)picture << 25) | (hurt ? 1L << 24 : 0L) | (long)tint;
            Material material;

            if (avatarMaterials.TryGetValue(key, out material) && material != null)
            {
                if (picture == 0 && blockAtlas != null && material.mainTexture != blockAtlas)
                {
                    material.mainTexture = blockAtlas;
                }

                return material;
            }

            Material basis = TexturedMaterial(0xFFFFFF);

            if (basis == null)
            {
                return null;
            }

            material = new Material(basis);

            // The same name as the blocks' own, so the search for a Subnautica material to copy passes it over.
            material.name = "MinecraftBlock";

            float red = ((tint >> 16) & 0xFF) / 255f;
            float green = ((tint >> 8) & 0xFF) / 255f;
            float blue = (tint & 0xFF) / 255f;
            material.color = hurt ? new Color(red, green * 0.45f, blue * 0.45f, 1f) : new Color(red, green, blue, 1f);

            SkinPicture skin;

            if (picture == 0)
            {
                material.mainTexture = blockAtlas != null ? (Texture)blockAtlas : Texture2D.whiteTexture;
            }
            else if (skins.TryGetValue(picture, out skin))
            {
                material.mainTexture = skin.picture;
            }
            else
            {
                material.mainTexture = Texture2D.whiteTexture;
            }

            avatarMaterials[key] = material;
            return material;
        }

        /// <summary>The plain white material things flash to (lit TNT, a creeper about to explode). Null if there is nothing to make it from yet.</summary>
        private Material WhiteMaterial()
        {
            if (flashMaterial == null)
            {
                Material basis = TexturedMaterial(0xFFFFFF);

                if (basis == null)
                {
                    return null;
                }

                // The same name as the blocks' own, so the search for a Subnautica material to copy passes it over.
                flashMaterial = new Material(basis);
                flashMaterial.name = "MinecraftBlock";
                flashMaterial.mainTexture = Texture2D.whiteTexture;
                flashMaterial.color = Color.white;
            }

            return flashMaterial;
        }

        // ---- Dry land for creepers ------------------------------------------------------------------

        /// <summary>
        /// "id x z [tall]": Minecraft asks whether there is open dry land at this spot to put a creeper
        /// (or, with a height given, an enderman) on. Look straight down from high above for Subnautica's terrain. If it is above the
        /// sea, flat enough to stand on, and nothing (a base, a wreck, one of Minecraft's
        /// blocks, a plant) is in the space a mob would stand in, answer "PROBED id y" with its
        /// height; otherwise "PROBED id -".
        /// </summary>
        private void HandleProbe(string text)
        {
            string[] parts = text.Split(' ');
            int id;
            float x, z;

            // How tall the mob is: a creeper unless Minecraft says otherwise (an enderman is nearly three blocks).
            float tall = 1.9f;

            if ((parts.Length != 3 && parts.Length != 4)
                || !int.TryParse(parts[0], NumberStyles.Integer, CultureInfo.InvariantCulture, out id)
                || !float.TryParse(parts[1], NumberStyles.Float, CultureInfo.InvariantCulture, out x)
                || !float.TryParse(parts[2], NumberStyles.Float, CultureInfo.InvariantCulture, out z)
                || (parts.Length == 4 && !float.TryParse(parts[3], NumberStyles.Float, CultureInfo.InvariantCulture, out tall)))
            {
                return;
            }

            // The space it would stand in, starting a little above the ground so a slope doesn't count as in the way.
            float halfTall = Mathf.Max(0.2f, (tall - 0.2f) / 2f);

            string answer = "-";
            RaycastHit ground;

            // z runs the other way in Subnautica.
            if (Physics.Raycast(new Vector3(x, 400f, -z), Vector3.down, out ground, 400f, 1 << LayerID.TerrainCollider, QueryTriggerInteraction.Ignore)
                && !IsMinecraftBlock(ground.collider)
                && ground.point.y > 1.5f && ground.normal.y >= FloorNormalY
                && !Physics.CheckBox(ground.point + Vector3.up * (0.2f + halfTall), new Vector3(0.3f, halfTall, 0.3f), Quaternion.identity, ~0, QueryTriggerInteraction.Ignore))
            {
                answer = ground.point.y.ToString("0.###", CultureInfo.InvariantCulture);
            }

            Send("PROBED " + id + " " + answer);
        }

        // ---- The grappling hook dragging creatures ----------------------------------------------------
        //
        // "GRAB id x y z": Minecraft's grappling hook has caught the creature at this spot.
        // "PULL id vx vy vz": the speed the rope is giving it, in blocks a tick. "LETGO id": the
        // hook has let go. While held, the creature is carried at that speed whatever it would
        // rather do, and Minecraft is told where it is ("HELD id x y z") so the hook and rope
        // stay on it; "HELD id -" says it has died or was never found.

        private class Grab
        {
            public LiveMixin life;
            public Rigidbody body;
            public Vector3 speed;
            public float nextReport;
        }

        private readonly Dictionary<int, Grab> grabs = new Dictionary<int, Grab>();
        private readonly List<int> grabScratch = new List<int>();

        /// <summary>Creatures a good deal bigger than a Gasopod, by Subnautica's own names for them. A grappling hook can't drag these; it pulls the player to them instead.</summary>
        private static readonly HashSet<string> BigCreatures = new HashSet<string>
        {
            "ReaperLeviathan", "GhostLeviathan", "GhostLeviathanJuvenile", "SeaDragon", "Reefback", "ReefbackBaby", "SeaTreader",
            "SeaEmperorJuvenile", "SeaEmperorBaby", "SeaEmperorLeviathan", "Shocker", "CrabSquid"
        };

        private static bool IsBigCreature(GameObject creature)
        {
            try
            {
                return BigCreatures.Contains(CraftData.GetTechType(creature).ToString());
            }
            catch (Exception)
            {
                return false;
            }
        }

        private void HandleGrab(string text, Player player)
        {
            string[] parts = text.Split(' ');
            int id;
            float x, y, z;

            if (player == null || parts.Length != 4
                || !int.TryParse(parts[0], NumberStyles.Integer, CultureInfo.InvariantCulture, out id)
                || !float.TryParse(parts[1], NumberStyles.Float, CultureInfo.InvariantCulture, out x)
                || !float.TryParse(parts[2], NumberStyles.Float, CultureInfo.InvariantCulture, out y)
                || !float.TryParse(parts[3], NumberStyles.Float, CultureInfo.InvariantCulture, out z))
            {
                return;
            }

            // The nearest living creature to the spot the hook struck.
            Vector3 point = new Vector3(x, y, -z);
            int count = Physics.OverlapSphereNonAlloc(point, 1.5f, overlapHits, ~0, QueryTriggerInteraction.Ignore);
            LiveMixin target = null;
            float nearest = float.MaxValue;

            for (int i = 0; i < count; i++)
            {
                Collider collider = overlapHits[i];

                if (collider == null || collider.transform.IsChildOf(player.transform) || collider.GetComponentInParent<Creature>() == null)
                {
                    continue;
                }

                LiveMixin life = collider.GetComponentInParent<LiveMixin>();

                if (life == null || life == player.liveMixin || life.health <= 0f)
                {
                    continue;
                }

                float distance = (collider.ClosestPointOnBounds(point) - point).sqrMagnitude;

                if (distance < nearest)
                {
                    nearest = distance;
                    target = life;
                }
            }

            if (target == null)
            {
                Send("HELD " + id + " -");
                return;
            }

            Grab grab = new Grab();
            grab.life = target;
            grab.body = target.GetComponentInParent<Rigidbody>();
            grabs[id] = grab;
        }

        private void HandlePull(string text)
        {
            string[] parts = text.Split(' ');
            int id;
            float x, y, z;
            Grab grab;

            if (parts.Length == 4
                && int.TryParse(parts[0], NumberStyles.Integer, CultureInfo.InvariantCulture, out id)
                && float.TryParse(parts[1], NumberStyles.Float, CultureInfo.InvariantCulture, out x)
                && float.TryParse(parts[2], NumberStyles.Float, CultureInfo.InvariantCulture, out y)
                && float.TryParse(parts[3], NumberStyles.Float, CultureInfo.InvariantCulture, out z)
                && grabs.TryGetValue(id, out grab))
            {
                // Blocks a tick become metres a second (20 ticks), with z flipped. Never faster than a brisk swim.
                grab.speed = Vector3.ClampMagnitude(new Vector3(x, y, -z) * 20f, 12f);
            }
        }

        /// <summary>Every frame: carry each held creature along, and keep Minecraft told where it is.</summary>
        private void TendGrabs()
        {
            if (grabs.Count == 0)
            {
                return;
            }

            grabScratch.Clear();

            foreach (KeyValuePair<int, Grab> entry in grabs)
            {
                Grab grab = entry.Value;

                if (grab.life == null || grab.life.health <= 0f)
                {
                    Send("HELD " + entry.Key + " -");
                    grabScratch.Add(entry.Key);
                    continue;
                }

                if (grab.body != null && !grab.body.isKinematic)
                {
                    // Its own swimming is overruled, not added to.
                    grab.body.velocity = grab.speed;
                }
                else
                {
                    grab.life.transform.position += grab.speed * Time.deltaTime;
                }

                if (Time.unscaledTime >= grab.nextReport)
                {
                    grab.nextReport = Time.unscaledTime + 0.05f;
                    Vector3 at = grab.life.transform.position;
                    Send(string.Format(CultureInfo.InvariantCulture, "HELD {0} {1:0.###} {2:0.###} {3:0.###}", entry.Key, at.x, at.y, at.z));
                }
            }

            foreach (int id in grabScratch)
            {
                grabs.Remove(id);
            }
        }

        private void RemoveAllAvatars()
        {
            grabs.Clear();

            foreach (Avatar avatar in avatars.Values)
            {
                RemoveAvatar(avatar);
            }

            avatars.Clear();
            avatarShapes.Clear();
            avatarMaterials.Clear();

            foreach (SkinPicture skin in skins.Values)
            {
                if (skin.picture != null)
                {
                    Destroy(skin.picture);
                }
            }

            skins.Clear();
            ShowDivers(null);
        }

        // ---- Nitrox's divers --------------------------------------------------------------------

        /// <summary>
        /// The marker Nitrox (the multiplayer mod) puts on the diver it shows for each other
        /// player, looked up by name so this mod works with or without Nitrox. Null without it.
        /// </summary>
        private Type DiverType()
        {
            if (diverType == null && Time.unscaledTime >= nextDiverTypeSearch)
            {
                nextDiverTypeSearch = Time.unscaledTime + 5f;

                foreach (Assembly assembly in AppDomain.CurrentDomain.GetAssemblies())
                {
                    try
                    {
                        Type found = assembly.GetType("NitroxClient.GameLogic.PlayerLogic.RemotePlayerIdentifier", false);

                        if (found != null)
                        {
                            diverType = found;
                            Logger.LogInfo("Nitrox is running: other players' divers are hidden behind their Minecraft characters");
                            break;
                        }
                    }
                    catch (Exception)
                    {
                        // An assembly that can't be searched; it isn't Nitrox.
                    }
                }
            }

            return diverType;
        }

        /// <summary>
        /// Every frame: hides each of Nitrox's divers that has a Minecraft character standing in
        /// its place, and shows any that no longer have. Run every frame because the game
        /// switches parts back on by itself.
        /// </summary>
        private void TendDivers()
        {
            Type type = DiverType();

            if (type == null)
            {
                return;
            }

            // Looking for the divers isn't free, so the list is only refreshed twice a second.
            if (Time.unscaledTime >= nextDiverScan)
            {
                nextDiverScan = Time.unscaledTime + 0.5f;
                divers = UnityEngine.Object.FindObjectsOfType(type);
            }

            diverScratch.Clear();

            foreach (UnityEngine.Object found in divers)
            {
                Component diver = found as Component;

                if (diver == null)
                {
                    continue;
                }

                GameObject body = diver.gameObject;
                Vector3 at = body.transform.position;
                bool replaced = false;

                foreach (Avatar avatar in avatars.Values)
                {
                    if (!avatar.self && !avatar.mob && (avatar.shownPlace - at).sqrMagnitude < 16f)
                    {
                        replaced = true;
                        break;
                    }
                }

                if (!replaced)
                {
                    continue;
                }

                diverScratch.Add(body);
                HashSet<Renderer> hidden;

                if (!hiddenDivers.TryGetValue(body, out hidden))
                {
                    hidden = new HashSet<Renderer>();
                    hiddenDivers[body] = hidden;
                }

                body.GetComponentsInChildren(true, bodyScratch);

                foreach (Renderer part in bodyScratch)
                {
                    if (part.enabled)
                    {
                        part.enabled = false;
                        hidden.Add(part);
                    }
                }
            }

            if (hiddenDivers.Count > diverScratch.Count)
            {
                ShowDivers(diverScratch);
            }
        }

        /// <summary>Shows Nitrox's divers again, except the ones listed (null for none).</summary>
        private void ShowDivers(List<GameObject> except)
        {
            if (hiddenDivers.Count == 0)
            {
                return;
            }

            List<GameObject> done = new List<GameObject>();

            foreach (KeyValuePair<GameObject, HashSet<Renderer>> entry in hiddenDivers)
            {
                if (except != null && entry.Key != null && except.Contains(entry.Key))
                {
                    continue;
                }

                foreach (Renderer part in entry.Value)
                {
                    // A part may have been destroyed since.
                    if (part != null)
                    {
                        part.enabled = true;
                    }
                }

                done.Add(entry.Key);
            }

            foreach (GameObject body in done)
            {
                hiddenDivers.Remove(body);
            }
        }

        // ---- Vehicles ----------------------------------------------------------------------------
        //
        // In a vehicle (the Seamoth), Subnautica is in charge of where the player is: the
        // character sits in the vehicle and goes where it goes, and this mod stops carrying it
        // to wherever Minecraft's player is. Minecraft is told where the seat is ("RIDE x y z
        // yaw", twenty times a second) and sits its player on an unseen mount there, so the
        // Minecraft character is seen sitting, takes no falls and bumps into nothing. Getting
        // out, Subnautica puts the player beside the vehicle and Minecraft starts again from
        // there ("SPAWN").
        //
        // Controls while piloting: the movement keys and the left mouse button are the
        // vehicle's. E still opens Minecraft's inventory, so getting out is a tap of Alt
        // instead. The scroll wheel moves along Minecraft's hotbar and the number keys pick the
        // vehicle's modules. The right mouse button is the vehicle's (its lights), unless
        // Minecraft's hand holds something to eat or drink: then it eats.

        /// <summary>True while the player is in a vehicle and linked. Read by the checks on Subnautica's buttons.</summary>
        private static bool ridingNow;
        private bool wasRiding;
        private Vehicle riddenVehicle;

        /// <summary>What is being ridden (a vehicle, or the Cyclops while at its helm) and what takes its damage.</summary>
        private Transform riddenBody;
        private LiveMixin riddenLife;

        /// <summary>How far in front the camera sits in the front view at the Cyclops's helm, in metres: outside the glass, looking in.</summary>
        private const float HelmViewDistance = 7f;
        private FieldInfo thrustField;

        /// <summary>How far back the camera sits in the outside views while in a vehicle, in metres: further than on foot, as on a horse in Minecraft.</summary>
        private const float VehicleViewDistance = 9f;
        private float nextRideReport;

        /// <summary>Whether Minecraft's hand holds something to eat or drink ("EDIBLE 1").</summary>
        private static bool foodInHand;

        /// <summary>Set while this mod itself asks Subnautica about a button, so its own question isn't answered "no".</summary>
        private static bool ownButtonCheck;

        /// <summary>Windows' number for the Alt key.</summary>
        private const int AltKey = 0x12;
        private static int altFrame = -1;
        private static bool altWasDown;
        private static bool altTapped;
        private static bool altClean;
        private static float altDownAt;

        private static readonly int ExitButton = ButtonNumber("Exit");
        private static readonly int RightHandButton = ButtonNumber("RightHand");
        private static readonly int CycleNextButton = ButtonNumber("CycleNext");
        private static readonly int CyclePrevButton = ButtonNumber("CyclePrev");

        /// <summary>The number Subnautica has for one of its buttons, looked up by name so a game update can't break the build; -1 if it has gone.</summary>
        private static int ButtonNumber(string name)
        {
            try
            {
                return (int)Enum.Parse(typeof(GameInput.Button), name);
            }
            catch (Exception)
            {
                return -1;
            }
        }

        /// <summary>
        /// Whether Alt was tapped this frame: pressed and let go again within half a second,
        /// with Subnautica still the window in front. Going by the letting go means Alt+Tab
        /// (which takes the window away before Alt comes back up) doesn't count. Worked out
        /// once a frame, whoever asks first.
        /// </summary>
        private static bool AltTapped()
        {
            if (altFrame != Time.frameCount)
            {
                altFrame = Time.frameCount;
                bool down = KeyHeld(AltKey);
                altTapped = false;

                if (down && !altWasDown)
                {
                    altDownAt = Time.unscaledTime;
                    altClean = Application.isFocused;
                }
                else if (down && (KeyHeld(0x09) || KeyHeld(0x0D) || !Application.isFocused))
                {
                    // Alt+Tab or Alt+Enter, or pressed while another window was in front: not a tap.
                    altClean = false;
                }
                else if (!down && altWasDown)
                {
                    altTapped = altClean && Application.isFocused && Time.unscaledTime - altDownAt < 0.5f && !typing && !screenOpen && !consoleOpen;
                }

                altWasDown = down;
            }

            return altTapped;
        }

        /// <summary>Runs after Subnautica words a "press this button" prompt. In a vehicle, the one for getting out names Alt.</summary>
        public static void ExitPrompt(string __0, GameInput.Button __1, ref string __result)
        {
            if ((int)__1 != ExitButton || !linkedNow)
            {
                return;
            }

            // Asked afresh rather than going by this mod's own note of it: a vehicle may word
            // its prompt in the same frame the player gets in, before that note is made.
            try
            {
                if (Player.main == null || (Player.main.GetVehicle() == null && !Player.main.isPiloting))
                {
                    return;
                }
            }
            catch (Exception)
            {
                return;
            }

            try
            {
                __result = string.Format(Language.main.Get(__0), "<color=#ADF8FFFF>Alt</color>");
            }
            catch (Exception)
            {
                // The wording isn't the shape expected: leave Subnautica's own prompt.
            }
        }

        /// <summary>
        /// Where the feet of a sitting Minecraft character belong, if it is in a vehicle: your
        /// own goes where your eyes are in the seat; another player's goes where Nitrox has
        /// their diver sitting. Minecraft's own idea of where they are trails behind a fast
        /// vehicle, so it is only used to tell which diver is theirs.
        /// </summary>
        private bool SeatOf(Avatar avatar, out Vector3 feet)
        {
            feet = Vector3.zero;

            if (avatar.mob)
            {
                return false;
            }

            if (avatar.self)
            {
                if (!ridingNow || Player.main == null)
                {
                    return false;
                }

                feet = EyePosition(Player.main) - Vector3.up * MinecraftEyeHeight;
                return true;
            }

            // Sitting in a vehicle, or walking about a Cyclops that is under way: either way
            // Nitrox keeps their diver in the right place aboard, and Minecraft's word trails behind.
            bool found = false;
            float nearest = 144f;

            foreach (UnityEngine.Object one in divers)
            {
                Component diver = one as Component;

                if (diver == null)
                {
                    continue;
                }

                if (!avatar.riding)
                {
                    SubRoot aboard = diver.GetComponentInParent<SubRoot>();

                    if (aboard == null || !aboard.isCyclops || !IsUnderWay(aboard.transform))
                    {
                        continue;
                    }
                }

                Vector3 at = diver.transform.position - Vector3.up * MinecraftEyeHeight;
                float away = (at - avatar.place).sqrMagnitude;

                if (away < nearest)
                {
                    nearest = away;
                    feet = at;
                    found = true;
                }
            }

            return found;
        }

        // ---- Aboard the Cyclops -----------------------------------------------------------------
        //
        // Walking about inside the Cyclops, Minecraft still moves the player. But Minecraft
        // knows nothing of the sub moving under their feet, so this side works out each frame
        // how far the hull has carried the spot the player is on, moves its own picture of the
        // player by that much at once, and tells Minecraft to move its player the same way
        // ("CARRY n dx dy dz"). Minecraft says when it has ("ACK n"). In between, the positions
        // Minecraft sends are still short by the carries it hasn't acted on yet, so those are
        // added on here (see CarryOwed).

        private Transform carrier;
        private Vector3 carrierPlace;
        private Quaternion carrierFacing;
        private Vector3 carryTotal;
        private Vector3 carrySent;
        private Vector3 carryAcked;
        private int carryNumber;
        private readonly Queue<KeyValuePair<int, Vector3>> carriesSent = new Queue<KeyValuePair<int, Vector3>>();

        /// <summary>How far the Cyclops has carried the player beyond what Minecraft has acted on so far.</summary>
        private Vector3 CarryOwed()
        {
            return carryTotal - carryAcked;
        }

        private void TendCarry(Player player)
        {
            Transform now = null;

            try
            {
                if (linked && !ridingNow && player != null && player.liveMixin != null && player.liveMixin.health > 0f)
                {
                    SubRoot around = player.currentSub;

                    if (around != null && around.isCyclops)
                    {
                        now = around.transform;
                    }
                }
            }
            catch (Exception)
            {
                now = null;
            }

            if (!linked)
            {
                carryTotal = carrySent = carryAcked = Vector3.zero;
                carriesSent.Clear();
            }

            if (now != carrier)
            {
                carrier = now;
            }
            else if (carrier != null && hasPlaced)
            {
                // Where the hull has taken the spot the player's eyes were over, turning included.
                Quaternion turn = carrier.rotation * Quaternion.Inverse(carrierFacing);
                Vector3 moved = carrier.position + turn * (lastEyes - carrierPlace) - lastEyes;

                if (moved.sqrMagnitude > 1e-10f && moved.sqrMagnitude < 25f)
                {
                    carryTotal += moved;
                    target += moved;
                    previousTarget += moved;
                    lastEyes += moved;
                }
            }

            if (carrier != null)
            {
                carrierPlace = carrier.position;
                carrierFacing = carrier.rotation;
            }

            Vector3 unsent = carryTotal - carrySent;

            if (unsent.sqrMagnitude > 0.0005f * 0.0005f)
            {
                carryNumber++;
                carrySent = carryTotal;
                carriesSent.Enqueue(new KeyValuePair<int, Vector3>(carryNumber, carryTotal));
                Send(string.Format(CultureInfo.InvariantCulture, "CARRY {0} {1:0.####} {2:0.####} {3:0.####}", carryNumber, unsent.x, unsent.y, unsent.z));
            }
            else if (carriesSent.Count == 0 && carrier == null && carryTotal != Vector3.zero)
            {
                // All settled: start the running total again from nothing, so it stays small and exact.
                carryTotal = carrySent = carryAcked = Vector3.zero;
            }
        }

        /// <summary>Where each Cyclops was last frame, and until when it counts as under way.</summary>
        private readonly Dictionary<Transform, Vector3> subPlaces = new Dictionary<Transform, Vector3>();
        private readonly Dictionary<Transform, float> subMovingUntil = new Dictionary<Transform, float>();
        private readonly Dictionary<Transform, int> subCheckedOn = new Dictionary<Transform, int>();

        /// <summary>Whether a sub has moved in the last half second.</summary>
        private bool IsUnderWay(Transform sub)
        {
            int checkedOn;

            if (!subCheckedOn.TryGetValue(sub, out checkedOn) || checkedOn != Time.frameCount)
            {
                subCheckedOn[sub] = Time.frameCount;
                Vector3 before;

                if (subPlaces.TryGetValue(sub, out before) && (sub.position - before).sqrMagnitude > 1e-6f)
                {
                    subMovingUntil[sub] = Time.unscaledTime + 0.5f;
                }

                subPlaces[sub] = sub.position;
            }

            float until;
            return subMovingUntil.TryGetValue(sub, out until) && Time.unscaledTime < until;
        }

        /// <summary>Every frame, once everything has moved: sitting characters are put exactly on their seats.</summary>
        private void SeatRiders()
        {
            foreach (Avatar avatar in avatars.Values)
            {
                Vector3 feet;

                if (avatar.thing != null && SeatOf(avatar, out feet))
                {
                    avatar.seated = true;
                    avatar.shownPlace = feet;
                    avatar.thing.transform.position = feet;
                }
                else
                {
                    avatar.seated = false;
                }
            }
        }

        // ---- Subnautica's command console: F9 ------------------------------------------------------
        //
        // F9 opens and closes Subnautica's own command console (for "spawn seamoth" and the
        // like), but only while the Minecraft world this player is in allows them cheats
        // (Minecraft says so with "CHEATS 1"). While it is open, nothing typed or clicked
        // reaches Minecraft, and the player doesn't move.

        /// <summary>Windows' number for the F9 key.</summary>
        private const int ConsoleKey = 0x78;
        private bool consoleKeyWasDown;
        private bool cheatsAllowed;

        /// <summary>True while Subnautica's command console is open. Checked by everything that reads keys for Minecraft.</summary>
        private static bool consoleOpen;
        private FieldInfo consoleInstance;
        private FieldInfo consoleState;
        private bool consoleLookupDone;

        private void WatchConsole()
        {
            const BindingFlags Any = BindingFlags.Instance | BindingFlags.Static | BindingFlags.Public | BindingFlags.NonPublic;

            try
            {
                if (!consoleLookupDone)
                {
                    consoleLookupDone = true;
                    consoleInstance = typeof(DevConsole).GetField("instance", Any);
                    consoleState = typeof(DevConsole).GetField("state", Any);

                    if (consoleInstance == null || consoleState == null)
                    {
                        Logger.LogWarning("Could not find Subnautica's command console, so F9 does nothing");
                    }
                }

                DevConsole console = consoleInstance != null && consoleState != null ? consoleInstance.GetValue(null) as DevConsole : null;

                if (console == null)
                {
                    consoleOpen = false;
                    return;
                }

                consoleOpen = (bool)consoleState.GetValue(console);

                bool down = linked && Application.isFocused && !typing && !screenOpen && KeyHeld(ConsoleKey);

                if (down && !consoleKeyWasDown)
                {
                    if (consoleOpen)
                    {
                        console.SetState(false);
                    }
                    else if (cheatsAllowed)
                    {
                        console.SetState(true);
                    }
                    else
                    {
                        ErrorMessage.AddMessage("Subnautica's console needs cheats allowed in the Minecraft world");
                    }

                    consoleOpen = (bool)consoleState.GetValue(console);
                }

                consoleKeyWasDown = down;
            }
            catch (Exception e)
            {
                if (warnedMissing.Add("console"))
                {
                    Logger.LogWarning("Could not work Subnautica's command console: " + e.Message);
                }
            }
        }

        // ---- Choosing a battery ----------------------------------------------------------------------

        private FieldInfo chooserManager;
        private bool chooserLookupDone;

        /// <summary>Whether Subnautica's battery chooser (R with a tool in hand) is on screen.</summary>
        private bool BatteryChooserOpen()
        {
            try
            {
                if (!chooserLookupDone)
                {
                    chooserLookupDone = true;
                    chooserManager = typeof(uGUI_ItemSelector).GetField("manager", BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic);
                }

                uGUI screen = uGUI.main;

                // The chooser remembers what it is choosing for; that is empty when it isn't up.
                return chooserManager != null && screen != null && screen.itemSelector != null && chooserManager.GetValue(screen.itemSelector) != null;
            }
            catch (Exception)
            {
                return false;
            }
        }

        // ---- What the held tool is doing ---------------------------------------------------------------
        //
        // "TOOLSTATE light lift": whether the tool in Subnautica's hand has its light on (a
        // flashlight or Seaglide switched on, with charge left), and whether it is lifting the
        // player (an inflated air bladder). Minecraft lights the surroundings and moves the
        // player to match. Sent whenever either changes.

        private string sentToolState;
        private float nextToolStateCheck;
        private FieldInfo bladderLifting;
        private bool bladderLookupDone;

        private void ReportToolState()
        {
            if (!linked || Time.unscaledTime < nextToolStateCheck)
            {
                return;
            }

            nextToolStateCheck = Time.unscaledTime + 0.1f;
            bool light = false;
            bool lift = false;

            try
            {
                Inventory inventory = Inventory.main;
                PlayerTool held = inventory != null ? inventory.GetHeldTool() : null;

                if (held != null)
                {
                    // A flashlight and a Seaglide both keep their switch in a "ToggleLights" part.
                    ToggleLights lights = held.GetComponentInChildren<ToggleLights>(true);

                    if (lights != null)
                    {
                        light = lights.GetLightsActive() && (lights.energyMixin == null || !lights.energyMixin.IsDepleted());
                    }

                    AirBladder bladder = held as AirBladder;

                    if (bladder != null)
                    {
                        if (!bladderLookupDone)
                        {
                            bladderLookupDone = true;
                            bladderLifting = typeof(AirBladder).GetField("applyBuoyancy", BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic);
                        }

                        lift = bladderLifting != null && (bool)bladderLifting.GetValue(bladder);
                    }
                }
            }
            catch (Exception e)
            {
                if (warnedMissing.Add("tool state"))
                {
                    Logger.LogWarning("Could not read what the held tool is doing: " + e.Message);
                }
            }

            string state = (light ? "1" : "0") + (lift ? " 1" : " 0");

            if (state != sentToolState)
            {
                sentToolState = state;
                Send("TOOLSTATE " + state);
            }
        }

        // ---- Subnautica's tool prompt, clear of Minecraft's hotbar ----------------------------------

        /// <summary>The lines of Subnautica's prompt that have been moved up, and the margins each had before.</summary>
        private readonly Dictionary<Component, Vector4> raisedPrompts = new Dictionary<Component, Vector4>();
        private float nextPromptCheck;

        /// <summary>How far up the prompt is moved, as a fraction of the screen's height.</summary>
        private const float PromptRise = 0.11f;

        /// <summary>
        /// Subnautica writes which buttons work the held tool along the bottom of the screen,
        /// right where Minecraft's hotbar is drawn. While Minecraft's HUD is showing, those
        /// lines are moved up clear of it; they go back when it isn't.
        ///
        /// Subnautica's own layout keeps putting the lines back where it wants them, so they
        /// are not moved as objects. Instead the writing is shifted inside its own box, by
        /// taking from the margin above it and adding the same to the margin below.
        /// </summary>
        private void RaiseToolPrompt()
        {
            if (Time.unscaledTime < nextPromptCheck)
            {
                return;
            }

            nextPromptCheck = Time.unscaledTime + 0.5f;

            try
            {
                bool raise = OverlayShowing();

                if (!raise)
                {
                    foreach (KeyValuePair<Component, Vector4> entry in raisedPrompts)
                    {
                        if (entry.Key != null)
                        {
                            PropertyInfo old = entry.Key.GetType().GetProperty("margin");

                            if (old != null)
                            {
                                old.SetValue(entry.Key, entry.Value, null);
                            }
                        }
                    }

                    raisedPrompts.Clear();
                    return;
                }

                HandReticle reticle = HandReticle.main;

                if (reticle == null)
                {
                    return;
                }

                foreach (string name in new[] { "compTextUse", "compTextUseSubscript" })
                {
                    FieldInfo field = typeof(HandReticle).GetField(name, BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic);
                    Component text = field != null ? field.GetValue(reticle) as Component : null;
                    PropertyInfo margin = text != null ? text.GetType().GetProperty("margin") : null;

                    if (margin == null)
                    {
                        continue;
                    }

                    // Measured against the whole screen area the text is laid out on: the topmost
                    // of the layout boxes it sits inside. Sizes in between may be scaled, so the
                    // distance is converted into the text's own units.
                    RectTransform area = text.transform as RectTransform;

                    while (area != null && area.parent is RectTransform)
                    {
                        area = (RectTransform)area.parent;
                    }

                    float own = text.transform.lossyScale.y;
                    float scale = area != null && own > 0.0001f ? area.lossyScale.y / own : 1f;
                    float rise = (area != null ? area.rect.height : 1080f) * PromptRise * scale;

                    Vector4 before;

                    if (!raisedPrompts.TryGetValue(text, out before))
                    {
                        before = (Vector4)margin.GetValue(text, null);
                        raisedPrompts[text] = before;

                        RectTransform box = text.transform as RectTransform;
                        Logger.LogInfo("Raising Subnautica's prompt " + PathOf(text.transform) + " by " + rise.ToString("0.#", CultureInfo.InvariantCulture)
                            + " (screen area " + (area != null ? area.rect.height.ToString("0", CultureInfo.InvariantCulture) : "?") + " high, box at "
                            + (box != null ? box.anchoredPosition.ToString() + " size " + box.rect.size.ToString() : "?") + ")");
                    }

                    // x left, y top, z right, w bottom. Set again whenever something has put it back.
                    Vector4 wanted = new Vector4(before.x, before.y - rise, before.z, before.w + rise);

                    if ((Vector4)margin.GetValue(text, null) != wanted)
                    {
                        margin.SetValue(text, wanted, null);
                    }
                }
            }
            catch (Exception e)
            {
                if (warnedMissing.Add("prompt"))
                {
                    Logger.LogWarning("Could not move Subnautica's tool prompt: " + e.Message);
                }
            }
        }

        // ---- Looking at yourself: F5 -------------------------------------------------------------

        /// <summary>F5 steps to the next view, and Minecraft is told so it can stop drawing the hand and start sending your own character.</summary>
        private void WatchViewKey()
        {
            bool playing = linked && Application.isFocused && Cursor.lockState == CursorLockMode.Locked && !typing && !screenOpen && !consoleOpen;
            bool down = playing && KeyHeld(ViewKey);

            if (down && !viewKeyWasDown)
            {
                viewMode = (viewMode + 1) % 3;
                Send("VIEW " + viewMode);
            }

            viewKeyWasDown = down;
        }

        /// <summary>
        /// Unity calls this just before a camera draws. In the outside views, the main camera is
        /// moved back from the eyes (or round to the front, facing back), stopping short of
        /// anything solid in the way so it never ends up inside a wall.
        /// </summary>
        private void BeforeCameraDraws(Camera camera)
        {
            if (outsideCamera == null || camera != outsideCamera || movedCamera != null)
            {
                return;
            }

            Player player = Player.main;

            if (player == null)
            {
                return;
            }

            Transform spot = camera.transform;
            movedFrom = spot.position;
            movedFromFacing = spot.rotation;
            movedCamera = camera;

            Vector3 forward = spot.forward;
            Vector3 direction = viewMode == 2 ? forward : -forward;

            // In a vehicle the camera sits further back, and the vehicle itself is not in its
            // way: it is what the view is of. Everything else solid still stops it.
            //
            // At the Cyclops's helm it is different: from behind, the camera stays inside the sub
            // like anywhere else aboard; from the front, it goes out through the glass and looks
            // back in at the helm.
            bool atHelm = ridingNow && riddenVehicle == null && riddenBody != null;
            Transform ridden = ridingNow && riddenBody != null && (!atHelm || viewMode == 2) ? riddenBody : null;
            float reach = ridden == null ? ViewDistance : atHelm ? HelmViewDistance : VehicleViewDistance;
            outsideHullNow = atHelm && ridden != null;
            float room = reach;

            RefreshSolidLayers(player);
            int count = Physics.SphereCastNonAlloc(movedFrom, 0.2f, direction, sweepHits, reach, solidLayers, QueryTriggerInteraction.Ignore);

            for (int i = 0; i < count; i++)
            {
                Collider inTheWay = sweepHits[i].collider;

                if (ridden != null && inTheWay != null && inTheWay.transform.IsChildOf(ridden))
                {
                    continue;
                }

                if (sweepHits[i].distance < room && IsScenery(inTheWay, player))
                {
                    room = sweepHits[i].distance;
                }
            }

            spot.position = movedFrom + direction * Mathf.Max(0f, room - 0.1f);

            if (viewMode == 2)
            {
                spot.rotation = Quaternion.LookRotation(-forward, spot.up);
            }
        }

        /// <summary>
        /// Puts the moved camera back when the frame is completely drawn. Not sooner: Subnautica's
        /// underwater haze is added after the scene itself, and has to see the camera where the
        /// scene was drawn from.
        /// </summary>
        private IEnumerator RestoreCameraAfterEachFrame()
        {
            WaitForEndOfFrame frameDrawn = new WaitForEndOfFrame();

            while (true)
            {
                yield return frameDrawn;
                RestoreCamera();
            }
        }

        private void RestoreCamera()
        {
            if (movedCamera == null)
            {
                // Either nothing was moved, or the camera has been destroyed since.
                movedCamera = null;
                outsideHullNow = false;
                return;
            }

            movedCamera.transform.position = movedFrom;
            movedCamera.transform.rotation = movedFromFacing;
            movedCamera = null;
            outsideHullNow = false;
        }

        // ---- Sending ------------------------------------------------------------------------

        private void Send(string line)
        {
            lock (writeLock)
            {
                if (writer == null)
                {
                    return;
                }

                try
                {
                    writer.WriteLine(line);
                    writer.Flush();
                }
                catch (Exception)
                {
                    // The connection dropped; the background thread will notice and reconnect.
                }
            }
        }

        // ---- Background thread: connecting and receiving --------------------------------------

        private void ConnectLoop()
        {
            while (running)
            {
                bool wasConnected = false;

                try
                {
                    using (TcpClient client = new TcpClient())
                    {
                        // If Minecraft stops reading (it has hung), give up on a line after a
                        // moment; without this Subnautica would hang with it.
                        client.SendTimeout = 3000;

                        // Fails straight away if Minecraft isn't running a world yet.
                        client.Connect("127.0.0.1", Port);
                        // Send every line at once instead of saving small ones up: Minecraft
                        // waits on the collision answers.
                        client.NoDelay = true;

                        using (NetworkStream stream = client.GetStream())
                        using (StreamReader reader = new StreamReader(stream, new UTF8Encoding(false)))
                        {
                            lock (writeLock)
                            {
                                writer = new StreamWriter(stream, new UTF8Encoding(false)) { NewLine = "\n" };
                            }

                            incoming.Enqueue(Connected);
                            wasConnected = true;

                            // Waits here for each line; ends when Minecraft closes the world.
                            string line;
                            while (running && (line = reader.ReadLine()) != null)
                            {
                                incoming.Enqueue(line);
                            }
                        }
                    }
                }
                catch (Exception)
                {
                    // Minecraft isn't there (yet), or the connection broke. Try again shortly.
                }
                finally
                {
                    lock (writeLock)
                    {
                        writer = null;
                    }

                    // However the connection ended (closed properly, or cut off because
                    // Minecraft crashed or was shut down), the game is told it has ended, so
                    // Subnautica gets its own controls back. (Until 2.18.1 a cut-off connection
                    // skipped this, leaving the player frozen with Minecraft gone.)
                    if (wasConnected)
                    {
                        incoming.Enqueue(Disconnected);
                    }
                }

                Thread.Sleep(RetryDelayMs);
            }
        }
    }
}
