package social.mycelium.android.ui.components

/**
 * Comprehensive emoji dataset organized by category.
 * Inspired by Amethyst's emoji support but themed for Mycelium.
 */
object EmojiData {

    data class EmojiCategory(
        val name: String,
        val icon: String,
        val emojis: List<String>
    )

    val categories: List<EmojiCategory> = listOf(
        EmojiCategory(
            name = "Smileys",
            icon = "😀",
            emojis = listOf(
                "😀", "😃", "😄", "😁", "😆", "😅", "🤣", "😂",
                "🙂", "🙃", "😉", "😊", "😇", "🥰", "😍", "🤩",
                "😘", "😗", "😚", "😙", "🥲", "😋", "😛", "😜",
                "🤪", "😝", "🤑", "🤗", "🤭", "🤫", "🤔", "🫡",
                "🤐", "🤨", "😐", "😑", "😶", "🫥", "😏", "😒",
                "🙄", "😬", "🤥", "😌", "😔", "😪", "🤤", "😴",
                "😷", "🤒", "🤕", "🤢", "🤮", "🥵", "🥶", "🥴",
                "😵", "🤯", "🤠", "🥳", "🥸", "😎", "🤓", "🧐",
                "😕", "🫤", "😟", "🙁", "😮", "😯", "😲", "😳",
                "🥺", "🥹", "😦", "😧", "😨", "😰", "😥", "😢",
                "😭", "😱", "😖", "😣", "😞", "😓", "😩", "😫",
                "🥱", "😤", "😡", "😠", "🤬", "😈", "👿", "💀",
                "☠️", "💩", "🤡", "👹", "👺", "👻", "👽", "👾",
                "🤖", "😺", "😸", "😹", "😻", "😼", "😽", "🙀",
                "😿", "😾"
            )
        ),
        EmojiCategory(
            name = "Gestures",
            icon = "👋",
            emojis = listOf(
                "👋", "🤚", "🖐️", "✋", "🖖", "🫱", "🫲", "🫳",
                "🫴", "👌", "🤌", "🤏", "✌️", "🤞", "🫰", "🤟",
                "🤘", "🤙", "👈", "👉", "👆", "🖕", "👇", "☝️",
                "🫵", "👍", "👎", "✊", "👊", "🤛", "🤜", "👏",
                "🙌", "🫶", "👐", "🤲", "🤝", "🙏", "✍️", "💅",
                "🤳", "💪", "🦾", "🦿", "🦵", "🦶", "👂", "🦻",
                "👃", "🧠", "🫀", "🫁", "🦷", "🦴", "👀", "👁️",
                "👅", "👄", "🫦", "💋", "❤️", "🧡", "💛", "💚",
                "💙", "💜", "🖤", "🤍", "🤎", "💔", "❤️‍🔥", "❤️‍🩹",
                "💕", "💞", "💓", "💗", "💖", "💘", "💝", "💟"
            )
        ),
        EmojiCategory(
            name = "People",
            icon = "👤",
            emojis = listOf(
                "👶", "👧", "🧒", "👦", "👩", "🧑", "👨", "👩‍🦱",
                "🧑‍🦱", "👨‍🦱", "👩‍🦰", "🧑‍🦰", "👨‍🦰", "👱‍♀️", "👱", "👱‍♂️",
                "👩‍🦳", "🧑‍🦳", "👨‍🦳", "👩‍🦲", "🧑‍🦲", "👨‍🦲", "🧔‍♀️", "🧔",
                "🧔‍♂️", "👵", "🧓", "👴", "👲", "👳‍♀️", "👳", "👳‍♂️",
                "🧕", "👮‍♀️", "👮", "👮‍♂️", "👷‍♀️", "👷", "👷‍♂️", "💂‍♀️",
                "💂", "💂‍♂️", "🕵️‍♀️", "🕵️", "🕵️‍♂️", "👩‍⚕️", "🧑‍⚕️", "👨‍⚕️",
                "👩‍🌾", "🧑‍🌾", "👨‍🌾", "👩‍🍳", "🧑‍🍳", "👨‍🍳", "👩‍🎓", "🧑‍🎓",
                "👨‍🎓", "👩‍🎤", "🧑‍🎤", "👨‍🎤", "👩‍🏫", "🧑‍🏫", "👨‍🏫", "👩‍🏭"
            )
        ),
        EmojiCategory(
            name = "Animals",
            icon = "🐸",
            emojis = listOf(
                "🐸", "🐶", "🐱", "🐭", "🐹", "🐰", "🦊", "🐻",
                "🐼", "🐻‍❄️", "🐨", "🐯", "🦁", "🐮", "🐷", "🐽",
                "🐵", "🙈", "🙉", "🙊", "🐒", "🐔", "🐧", "🐦",
                "🐤", "🐣", "🐥", "🦆", "🦅", "🦉", "🦇", "🐺",
                "🐗", "🐴", "🦄", "🐝", "🪱", "🐛", "🦋", "🐌",
                "🐞", "🐜", "🪰", "🪲", "🪳", "🦟", "🦗", "🕷️",
                "🕸️", "🦂", "🐢", "🐍", "🦎", "🦖", "🦕", "🐙",
                "🦑", "🦐", "🦞", "🦀", "🐡", "🐠", "🐟", "🐬",
                "🐳", "🐋", "🦈", "🦭", "🐊", "🐅", "🐆", "🦓",
                "🦍", "🦧", "🐘", "🦛", "🦏", "🐪", "🐫", "🦒",
                "🦘", "🦬", "🐃", "🐂", "🐄", "🐎", "🐖", "🐏",
                "🐑", "🦙", "🐐", "🦌", "🐕", "🐩", "🦮", "🐕‍🦺"
            )
        ),
        EmojiCategory(
            name = "Food",
            icon = "🍕",
            emojis = listOf(
                "🍎", "🍐", "🍊", "🍋", "🍌", "🍉", "🍇", "🍓",
                "🫐", "🍈", "🍒", "🍑", "🥭", "🍍", "🥥", "🥝",
                "🍅", "🍆", "🥑", "🥦", "🥬", "🥒", "🌶️", "🫑",
                "🌽", "🥕", "🫒", "🧄", "🧅", "🥔", "🍠", "🥐",
                "🍞", "🥖", "🥨", "🧀", "🥚", "🍳", "🧈", "🥞",
                "🧇", "🥓", "🥩", "🍗", "🍖", "🦴", "🌭", "🍔",
                "🍟", "🍕", "🫓", "🥪", "🥙", "🧆", "🌮", "🌯",
                "🫔", "🥗", "🥘", "🫕", "🍝", "🍜", "🍲", "🍛",
                "🍣", "🍱", "🥟", "🦪", "🍤", "🍙", "🍚", "🍘",
                "🍥", "🥠", "🥮", "🍢", "🍡", "🍧", "🍨", "🍦",
                "🥧", "🧁", "🍰", "🎂", "🍮", "🍭", "🍬", "🍫",
                "🍿", "🍩", "🍪", "🌰", "🥜", "🍯", "🥛", "🍼",
                "☕", "🫖", "🍵", "🧃", "🥤", "🧋", "🍶", "🍺",
                "🍻", "🥂", "🍷", "🥃", "🍸", "🍹", "🧉", "🍾"
            )
        ),
        EmojiCategory(
            name = "Travel",
            icon = "✈️",
            emojis = listOf(
                "🚗", "🚕", "🚙", "🚌", "🚎", "🏎️", "🚓", "🚑",
                "🚒", "🚐", "🛻", "🚚", "🚛", "🚜", "🏍️", "🛵",
                "🚲", "🛴", "🛹", "🛼", "🚏", "🛣️", "🛤️", "⛽",
                "🚨", "🚥", "🚦", "🛑", "🚧", "⚓", "🛟", "⛵",
                "🛶", "🚤", "🛳️", "⛴️", "🛥️", "🚢", "✈️", "🛩️",
                "🛫", "🛬", "🪂", "💺", "🚁", "🚟", "🚠", "🚡",
                "🛰️", "🚀", "🛸", "🌍", "🌎", "🌏", "🌐", "🗺️",
                "🧭", "🏔️", "⛰️", "🌋", "🗻", "🏕️", "🏖️", "🏜️",
                "🏝️", "🏞️", "🏟️", "🏛️", "🏗️", "🧱", "🪨", "🪵",
                "🛖", "🏘️", "🏚️", "🏠", "🏡", "🏢", "🏣", "🏤"
            )
        ),
        EmojiCategory(
            name = "Activities",
            icon = "⚽",
            emojis = listOf(
                "⚽", "🏀", "🏈", "⚾", "🥎", "🎾", "🏐", "🏉",
                "🥏", "🎱", "🪀", "🏓", "🏸", "🏒", "🏑", "🥍",
                "🏏", "🪃", "🥅", "⛳", "🪁", "🏹", "🎣", "🤿",
                "🥊", "🥋", "🎽", "🛹", "🛼", "🛷", "⛸️", "🥌",
                "🎿", "⛷️", "🏂", "🪂", "🏋️", "🤼", "🤸", "⛹️",
                "🤺", "🤾", "🏌️", "🏇", "🧘", "🏄", "🏊", "🤽",
                "🚣", "🧗", "🚵", "🚴", "🏆", "🥇", "🥈", "🥉",
                "🏅", "🎖️", "🏵️", "🎗️", "🎫", "🎟️", "🎪", "🎭",
                "🎨", "🎬", "🎤", "🎧", "🎼", "🎹", "🥁", "🪘",
                "🎷", "🎺", "🪗", "🎸", "🪕", "🎻", "🎲", "♟️",
                "🎯", "🎳", "🎮", "🕹️", "🎰"
            )
        ),
        EmojiCategory(
            name = "Objects",
            icon = "💡",
            emojis = listOf(
                "⌚", "📱", "📲", "💻", "⌨️", "🖥️", "🖨️", "🖱️",
                "🖲️", "🕹️", "🗜️", "💽", "💾", "💿", "📀", "📼",
                "📷", "📸", "📹", "🎥", "📽️", "🎞️", "📞", "☎️",
                "📟", "📠", "📺", "📻", "🎙️", "🎚️", "🎛️", "🧭",
                "⏱️", "⏲️", "⏰", "🕰️", "⌛", "⏳", "📡", "🔋",
                "🪫", "🔌", "💡", "🔦", "🕯️", "🪔", "🧯", "🛢️",
                "💸", "💵", "💴", "💶", "💷", "🪙", "💰", "💳",
                "💎", "⚖️", "🪜", "🧰", "🪛", "🔧", "🔨", "⚒️",
                "🛠️", "⛏️", "🪚", "🔩", "⚙️", "🪤", "🧲", "🔫",
                "💣", "🧨", "🪓", "🔪", "🗡️", "⚔️", "🛡️", "🚬"
            )
        ),
        EmojiCategory(
            name = "Symbols",
            icon = "⭐",
            emojis = listOf(
                "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍",
                "🤎", "💔", "❤️‍🔥", "❤️‍🩹", "❣️", "💕", "💞", "💓",
                "💗", "💖", "💘", "💝", "💟", "☮️", "✝️", "☪️",
                "🕉️", "☸️", "✡️", "🔯", "🕎", "☯️", "☦️", "🛐",
                "⛎", "♈", "♉", "♊", "♋", "♌", "♍", "♎",
                "♏", "♐", "♑", "♒", "♓", "🆔", "⚛️", "🉑",
                "☢️", "☣️", "📴", "📳", "🈶", "🈚", "🈸", "🈺",
                "🈷️", "✴️", "🆚", "💮", "🉐", "㊙️", "㊗️", "🈴",
                "🈵", "🈹", "🈲", "🅰️", "🅱️", "🆎", "🆑", "🅾️",
                "🆘", "❌", "⭕", "🛑", "⛔", "📛", "🚫", "💯",
                "💢", "♨️", "🚷", "🚯", "🚳", "🚱", "🔞", "📵",
                "🚭", "❗", "❕", "❓", "❔", "‼️", "⁉️", "🔅",
                "🔆", "〽️", "⚠️", "🚸", "🔱", "⚜️", "🔰", "♻️",
                "✅", "🈯", "💹", "❇️", "✳️", "❎", "🌐", "💠",
                "Ⓜ️", "🌀", "💤", "🏧", "🚾", "♿", "🅿️", "🛗",
                "🈳", "🈂️", "🛂", "🛃", "🛄", "🛅", "⬆️", "↗️",
                "➡️", "↘️", "⬇️", "↙️", "⬅️", "↖️", "↕️", "↔️",
                "↩️", "↪️", "⤴️", "⤵️", "🔃", "🔄", "🔙", "🔚",
                "🔛", "🔜", "🔝", "⭐", "🌟", "💫", "✨", "⚡",
                "🔥", "💥", "☄️", "🌈", "☀️", "🌤️", "⛅", "🌥️"
            )
        ),
        EmojiCategory(
            name = "Flags",
            icon = "🏳️",
            emojis = listOf(
                "🏳️", "🏴", "🏴‍☠️", "🏁", "🚩", "🎌", "🏳️‍🌈", "🏳️‍⚧️",
                "🇺🇸", "🇬🇧", "🇨🇦", "🇦🇺", "🇩🇪", "🇫🇷", "🇯🇵", "🇰🇷",
                "🇨🇳", "🇮🇳", "🇧🇷", "🇲🇽", "🇪🇸", "🇮🇹", "🇷🇺", "🇳🇱",
                "🇸🇪", "🇳🇴", "🇩🇰", "🇫🇮", "🇵🇱", "🇺🇦", "🇹🇷", "🇿🇦",
                "🇦🇷", "🇨🇴", "🇨🇱", "🇵🇪", "🇻🇪", "🇪🇨", "🇵🇹", "🇬🇷",
                "🇮🇪", "🇨🇭", "🇦🇹", "🇧🇪", "🇸🇬", "🇹🇭", "🇻🇳", "🇵🇭",
                "🇮🇩", "🇲🇾", "🇳🇿", "🇪🇬", "🇳🇬", "🇰🇪", "🇬🇭", "🇸🇦",
                "🇦🇪", "🇮🇱", "🇯🇴", "🇱🇧", "🇮🇶", "🇮🇷", "🇵🇰", "🇧🇩"
            )
        )
    )

    /** Quick-access default emojis shown at top before recent list. */
    val quickAccessDefaults = listOf(
        "❤️", "🔥", "👍", "👎", "😂", "😍", "🤔", "👀",
        "🙏", "💯", "⚡", "🎉", "🫡", "🤝", "🐸", "🚀"
    )

    /** Precomputed flat list of all emojis across categories (computed once). */
    val allEmojis: List<String> by lazy {
        categories.flatMap { it.emojis }
    }

    /**
     * Emoji keyword map: maps lowercase keywords to emojis.
     * Enables Android-keyboard-style search (e.g. "heart" → ❤️, "fire" → 🔥).
     */
    val emojiKeywords: Map<String, List<String>> by lazy {
        val m = mutableMapOf<String, MutableList<String>>()
        fun add(keyword: String, emoji: String) {
            m.getOrPut(keyword) { mutableListOf() }.add(emoji)
        }
        // Smileys
        add("smile", "😀"); add("smile", "😃"); add("smile", "😄"); add("grin", "😁"); add("grin", "😀")
        add("laugh", "😆"); add("laugh", "😅"); add("laugh", "🤣"); add("laugh", "😂"); add("joy", "😂")
        add("rofl", "🤣"); add("happy", "😊"); add("happy", "😀"); add("happy", "😃"); add("blush", "😊")
        add("wink", "😉"); add("angel", "😇"); add("love", "🥰"); add("love", "😍"); add("love", "😘")
        add("heart eyes", "😍"); add("star struck", "🤩"); add("kiss", "😘"); add("kiss", "😗"); add("kiss", "😚"); add("kiss", "😙")
        add("yum", "😋"); add("tongue", "😛"); add("tongue", "😜"); add("tongue", "😝"); add("crazy", "🤪")
        add("money", "🤑"); add("hug", "🤗"); add("think", "🤔"); add("thinking", "🤔"); add("shush", "🤫"); add("quiet", "🤫")
        add("salute", "🫡"); add("zip", "🤐"); add("raised eyebrow", "🤨"); add("neutral", "😐"); add("blank", "😑")
        add("smirk", "😏"); add("unamused", "😒"); add("eye roll", "🙄"); add("grimace", "😬"); add("liar", "🤥")
        add("relieved", "😌"); add("sad", "😔"); add("sad", "😢"); add("sad", "😞"); add("sleepy", "😪"); add("drool", "🤤")
        add("sleep", "😴"); add("zzz", "😴"); add("sick", "😷"); add("sick", "🤒"); add("sick", "🤕"); add("sick", "🤢")
        add("vomit", "🤮"); add("hot", "🥵"); add("cold", "🥶"); add("dizzy", "🥴"); add("explode", "🤯")
        add("cowboy", "🤠"); add("party", "🥳"); add("disguise", "🥸"); add("cool", "😎"); add("sunglasses", "😎")
        add("nerd", "🤓"); add("monocle", "🧐"); add("confused", "😕"); add("worried", "😟"); add("frown", "🙁")
        add("surprise", "😮"); add("surprise", "😲"); add("shock", "😱"); add("plead", "🥺"); add("cry", "😢"); add("cry", "😭")
        add("sob", "😭"); add("scream", "😱"); add("angry", "😡"); add("angry", "😠"); add("rage", "😡"); add("swear", "🤬")
        add("devil", "😈"); add("devil", "👿"); add("skull", "💀"); add("death", "💀"); add("poop", "💩"); add("poo", "💩")
        add("clown", "🤡"); add("ogre", "👹"); add("goblin", "👺"); add("ghost", "👻"); add("alien", "👽")
        add("robot", "🤖"); add("cat", "😺"); add("cat", "😸"); add("cat", "😻")
        // Gestures & Body
        add("wave", "👋"); add("hand", "✋"); add("hand", "🖐️"); add("ok", "👌"); add("pinch", "🤏")
        add("peace", "✌️"); add("cross fingers", "🤞"); add("rock", "🤘"); add("call", "🤙")
        add("point", "👈"); add("point", "👉"); add("point", "👆"); add("point", "👇")
        add("thumbs up", "👍"); add("thumbsup", "👍"); add("like", "👍"); add("yes", "👍")
        add("thumbs down", "👎"); add("thumbsdown", "👎"); add("dislike", "👎"); add("no", "👎")
        add("fist", "✊"); add("fist", "👊"); add("clap", "👏"); add("hands", "🙌"); add("pray", "🙏"); add("prayer", "🙏")
        add("handshake", "🤝"); add("muscle", "💪"); add("strong", "💪"); add("flex", "💪")
        add("eye", "👀"); add("eyes", "👀"); add("brain", "🧠")
        // Hearts
        add("heart", "❤️"); add("heart", "🧡"); add("heart", "💛"); add("heart", "💚"); add("heart", "💙")
        add("heart", "💜"); add("heart", "🖤"); add("heart", "🤍"); add("heart", "🤎"); add("heart", "💕")
        add("heart", "💖"); add("heart", "💗"); add("heart", "💘"); add("heart", "💝"); add("heart", "💟")
        add("broken heart", "💔"); add("heartbreak", "💔")
        add("red heart", "❤️"); add("orange heart", "🧡"); add("yellow heart", "💛"); add("green heart", "💚")
        add("blue heart", "💙"); add("purple heart", "💜"); add("black heart", "🖤"); add("white heart", "🤍")
        // Animals
        add("frog", "🐸"); add("dog", "🐶"); add("cat", "🐱"); add("mouse", "🐭"); add("hamster", "🐹")
        add("rabbit", "🐰"); add("bunny", "🐰"); add("fox", "🦊"); add("bear", "🐻"); add("panda", "🐼")
        add("koala", "🐨"); add("tiger", "🐯"); add("lion", "🦁"); add("cow", "🐮"); add("pig", "🐷")
        add("monkey", "🐵"); add("monkey", "🙈"); add("monkey", "🙉"); add("monkey", "🙊")
        add("chicken", "🐔"); add("penguin", "🐧"); add("bird", "🐦"); add("eagle", "🦅"); add("owl", "🦉")
        add("bat", "🦇"); add("wolf", "🐺"); add("horse", "🐴"); add("unicorn", "🦄"); add("bee", "🐝")
        add("butterfly", "🦋"); add("snail", "🐌"); add("bug", "🐛"); add("spider", "🕷️")
        add("turtle", "🐢"); add("snake", "🐍"); add("dragon", "🦖"); add("dinosaur", "🦕")
        add("octopus", "🐙"); add("fish", "🐟"); add("dolphin", "🐬"); add("whale", "🐳"); add("shark", "🦈")
        add("elephant", "🐘"); add("giraffe", "🦒")
        // Food
        add("apple", "🍎"); add("pizza", "🍕"); add("burger", "🍔"); add("fries", "🍟"); add("hot dog", "🌭")
        add("taco", "🌮"); add("burrito", "🌯"); add("sushi", "🍣"); add("rice", "🍚"); add("ramen", "🍜")
        add("pasta", "🍝"); add("bread", "🍞"); add("cheese", "🧀"); add("egg", "🥚"); add("cookie", "🍪")
        add("cake", "🎂"); add("cake", "🍰"); add("pie", "🥧"); add("chocolate", "🍫"); add("candy", "🍬")
        add("donut", "🍩"); add("ice cream", "🍦"); add("coffee", "☕"); add("tea", "🍵"); add("beer", "🍺")
        add("wine", "🍷"); add("cocktail", "🍸"); add("champagne", "🍾"); add("drink", "🥤")
        add("watermelon", "🍉"); add("grape", "🍇"); add("strawberry", "🍓"); add("banana", "🍌")
        add("orange", "🍊"); add("lemon", "🍋"); add("peach", "🍑"); add("cherry", "🍒")
        add("avocado", "🥑"); add("broccoli", "🥦"); add("corn", "🌽"); add("carrot", "🥕"); add("pepper", "🌶️")
        // Travel & Places
        add("car", "🚗"); add("bus", "🚌"); add("train", "🚄"); add("plane", "✈️"); add("rocket", "🚀")
        add("ship", "🚢"); add("boat", "⛵"); add("bike", "🚲"); add("motorcycle", "🏍️")
        add("earth", "🌍"); add("globe", "🌎"); add("world", "🌏"); add("map", "🗺️")
        add("mountain", "🏔️"); add("volcano", "🌋"); add("beach", "🏖️"); add("desert", "🏜️")
        add("house", "🏠"); add("home", "🏠"); add("building", "🏢"); add("hospital", "🏥")
        add("moon", "🌙"); add("sun", "☀️"); add("star", "⭐"); add("stars", "✨"); add("sparkle", "✨")
        // Activities & Objects
        add("soccer", "⚽"); add("football", "🏈"); add("basketball", "🏀"); add("baseball", "⚾")
        add("tennis", "🎾"); add("golf", "⛳"); add("trophy", "🏆"); add("medal", "🥇")
        add("art", "🎨"); add("paint", "🎨"); add("music", "🎵"); add("music", "🎶"); add("guitar", "🎸")
        add("microphone", "🎤"); add("headphone", "🎧"); add("game", "🎮"); add("controller", "🎮"); add("dice", "🎲")
        add("phone", "📱"); add("computer", "💻"); add("laptop", "💻"); add("keyboard", "⌨️")
        add("camera", "📷"); add("tv", "📺"); add("light", "💡"); add("bulb", "💡"); add("battery", "🔋")
        add("money", "💰"); add("dollar", "💵"); add("gem", "💎"); add("diamond", "💎")
        add("tool", "🔧"); add("wrench", "🔧"); add("hammer", "🔨"); add("bomb", "💣"); add("gun", "🔫")
        // Symbols
        add("check", "✅"); add("checkmark", "✅"); add("x", "❌"); add("cross", "❌"); add("warning", "⚠️")
        add("question", "❓"); add("exclamation", "❗"); add("hundred", "💯"); add("100", "💯")
        add("fire", "🔥"); add("flame", "🔥"); add("lightning", "⚡"); add("bolt", "⚡"); add("zap", "⚡")
        add("boom", "💥"); add("explosion", "💥"); add("rainbow", "🌈"); add("sparkles", "✨")
        add("celebration", "🎉"); add("tada", "🎉"); add("confetti", "🎉"); add("balloon", "🎈")
        add("gift", "🎁"); add("present", "🎁"); add("ribbon", "🎀")
        // Flags
        add("flag", "🏳️"); add("pirate", "🏴‍☠️"); add("rainbow flag", "🏳️‍🌈")
        add("usa", "🇺🇸"); add("america", "🇺🇸"); add("uk", "🇬🇧"); add("canada", "🇨🇦")
        add("japan", "🇯🇵"); add("korea", "🇰🇷"); add("china", "🇨🇳"); add("india", "🇮🇳")
        add("brazil", "🇧🇷"); add("france", "🇫🇷"); add("germany", "🇩🇪"); add("italy", "🇮🇹")
        m
    }

    /** Search emojis by keyword/name. Returns matching emojis, deduplicated. */
    fun searchByKeyword(query: String): List<String> {
        if (query.isBlank()) return emptyList()
        val q = query.lowercase().trim()
        val results = mutableListOf<String>()
        // Exact keyword matches first
        emojiKeywords.forEach { (keyword, emojis) ->
            if (keyword.contains(q)) results.addAll(emojis)
        }
        return results.distinct()
    }
}
