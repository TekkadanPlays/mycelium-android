package social.mycelium.android.data

/**
 * Sample data for @Preview composables only. Not used at runtime.
 * Runtime feed and thread data come from relays (NotesRepository, Kind1RepliesRepository).
 */
object SampleData {
    val sampleAuthors = listOf(
        Author(
            id = "1",
            username = "johndoe",
            displayName = "John Doe",
            avatarUrl = null,
            isVerified = true
        ),
        Author(
            id = "2",
            username = "janesmith",
            displayName = "Jane Smith",
            avatarUrl = null,
            isVerified = false
        ),
        Author(
            id = "3",
            username = "techguru",
            displayName = "Tech Guru",
            avatarUrl = null,
            isVerified = true
        ),
        Author(
            id = "4",
            username = "artlover",
            displayName = "Art Lover",
            avatarUrl = null,
            isVerified = false
        ),
        Author(
            id = "5",
            username = "traveler",
            displayName = "World Traveler",
            avatarUrl = null,
            isVerified = true
        ),
        Author(
            id = "6",
            username = "tekkadan",
            displayName = "🐸 Tekkadan",
            avatarUrl = null,
            isVerified = true
        )
    )

    val sampleNotes = listOf(
        Note(
            id = "announcement-1",
            author = sampleAuthors[5], // Tekkadan
            content = "🎉 Exciting news! Check out the new zap menus in notes and comments! ⚡️ Feedback is warmly welcomed at https://github.com/TekkadanPlays/mycelium-android/discussions/3 🐸",
            timestamp = System.currentTimeMillis() - 1800000, // 30 minutes ago
            likes = 156,
            shares = 34,
            comments = 28,
            isLiked = false,
            hashtags = listOf("Mycelium", "Updates", "ZapMenus", "Feedback"),
            urlPreviews = listOf(
                UrlPreviewInfo(
                    url = "https://github.com/TekkadanPlays/mycelium-android/discussions",
                    title = "Mycelium Discussions",
                    description = "GitHub Discussions for Mycelium - a modern social media platform",
                    imageUrl = "https://github.githubassets.com/images/modules/site/social-cards/github-social.png",
                    siteName = "github.com"
                )
            )
        ),
        Note(
            id = "1",
            author = sampleAuthors[0],
            content = "Just finished reading an amazing book about AI and machine learning. The future is truly fascinating! 🤖📚 #AI #MachineLearning #Tech",
            timestamp = System.currentTimeMillis() - 3600000, // 1 hour ago
            likes = 42,
            shares = 8,
            comments = 12,
            isLiked = false,
            hashtags = listOf("AI", "MachineLearning", "Tech")
        ),
        Note(
            id = "2",
            author = sampleAuthors[1],
            content = "Beautiful sunset today! Sometimes you just need to stop and appreciate the simple things in life. 🌅 #Sunset #Nature #Mindfulness",
            timestamp = System.currentTimeMillis() - 7200000, // 2 hours ago
            likes = 28,
            shares = 5,
            comments = 7,
            isLiked = true,
            hashtags = listOf("Sunset", "Nature", "Mindfulness")
        ),
        Note(
            id = "3",
            author = sampleAuthors[2],
            content = "Working on a new project that's going to revolutionize how we think about productivity. Can't wait to share more details soon! 💻 Check out this amazing resource: https://www.notion.so/productivity-guide #Productivity #Innovation #Startup",
            timestamp = System.currentTimeMillis() - 10800000, // 3 hours ago
            likes = 67,
            shares = 15,
            comments = 23,
            isLiked = false,
            hashtags = listOf("Productivity", "Innovation", "Startup"),
            urlPreviews = listOf(
                UrlPreviewInfo(
                    url = "https://www.notion.so/productivity-guide",
                    title = "The Ultimate Productivity Guide",
                    description = "Learn how to maximize your productivity with proven techniques and tools",
                    imageUrl = "https://www.notion.so/images/page-cover/woodcuts_1.jpg",
                    siteName = "notion.so"
                )
            )
        ),
        Note(
            id = "4",
            author = sampleAuthors[3],
            content = "Visited the local art gallery today. The contemporary pieces were absolutely stunning! Art has such a powerful way of expressing emotions. 🎨 #Art #Gallery #Contemporary",
            timestamp = System.currentTimeMillis() - 14400000, // 4 hours ago
            likes = 35,
            shares = 3,
            comments = 9,
            isLiked = false,
            hashtags = listOf("Art", "Gallery", "Contemporary")
        ),
        Note(
            id = "5",
            author = sampleAuthors[4],
            content = "Just landed in Tokyo! The city is even more incredible than I imagined. The blend of traditional and modern architecture is breathtaking. ✈️🇯🇵 #Travel #Tokyo #Japan #Architecture",
            timestamp = System.currentTimeMillis() - 18000000, // 5 hours ago
            likes = 89,
            shares = 22,
            comments = 31,
            isLiked = true,
            hashtags = listOf("Travel", "Tokyo", "Japan", "Architecture")
        ),
        Note(
            id = "6",
            author = sampleAuthors[0],
            content = "Coffee and coding - the perfect combination for a productive morning! ☕️💻 #Coffee #Coding #Productivity #Morning",
            timestamp = System.currentTimeMillis() - 21600000, // 6 hours ago
            likes = 19,
            shares = 2,
            comments = 4,
            isLiked = false,
            hashtags = listOf("Coffee", "Coding", "Productivity", "Morning")
        ),
        Note(
            id = "7",
            author = sampleAuthors[1],
            content = "Just finished a 10K run in the park. Nothing beats the feeling of accomplishment after a good workout! 🏃‍♀️ #Fitness #Running #Health #Motivation",
            timestamp = System.currentTimeMillis() - 25200000, // 7 hours ago
            likes = 56,
            shares = 11,
            comments = 18,
            isLiked = true,
            hashtags = listOf("Fitness", "Running", "Health", "Motivation")
        ),
        Note(
            id = "8",
            author = sampleAuthors[2],
            content = "The key to successful software development isn't just writing code - it's understanding the problem you're solving and the people you're solving it for. #SoftwareDevelopment #ProblemSolving #UserExperience",
            timestamp = System.currentTimeMillis() - 28800000, // 8 hours ago
            likes = 73,
            shares = 19,
            comments = 27,
            isLiked = false,
            hashtags = listOf("SoftwareDevelopment", "ProblemSolving", "UserExperience")
        )
    )

    // Sample comments with deep nesting for testing conversation threads
    val sampleComments = listOf(
        Comment(
            id = "c1",
            author = sampleAuthors[0],
            content = "This is such an interesting topic! I've been thinking about this a lot lately.",
            timestamp = System.currentTimeMillis() - 5000000,
            likes = 12,
            isLiked = false
        ),
        Comment(
            id = "c2",
            author = sampleAuthors[1],
            content = "Completely agree! This is going to change everything.",
            timestamp = System.currentTimeMillis() - 4500000,
            likes = 8,
            isLiked = true
        ),
        Comment(
            id = "c3",
            author = sampleAuthors[2],
            content = "I have a different perspective on this...",
            timestamp = System.currentTimeMillis() - 4000000,
            likes = 15,
            isLiked = false
        ),
        Comment(
            id = "c4",
            author = sampleAuthors[3],
            content = "Can you elaborate more on this point?",
            timestamp = System.currentTimeMillis() - 3500000,
            likes = 5,
            isLiked = false
        ),
        Comment(
            id = "c5",
            author = sampleAuthors[4],
            content = "This reminds me of something I read recently.",
            timestamp = System.currentTimeMillis() - 3000000,
            likes = 7,
            isLiked = false
        ),
        Comment(
            id = "c6",
            author = sampleAuthors[0],
            content = "Sure! What I meant was...",
            timestamp = System.currentTimeMillis() - 2500000,
            likes = 10,
            isLiked = true
        ),
        Comment(
            id = "c7",
            author = sampleAuthors[1],
            content = "That's a great explanation, thanks!",
            timestamp = System.currentTimeMillis() - 2000000,
            likes = 3,
            isLiked = false
        ),
        Comment(
            id = "c8",
            author = sampleAuthors[2],
            content = "I see where you're coming from now.",
            timestamp = System.currentTimeMillis() - 1500000,
            likes = 6,
            isLiked = false
        ),
        Comment(
            id = "c9",
            author = sampleAuthors[3],
            content = "But what about the edge cases?",
            timestamp = System.currentTimeMillis() - 1000000,
            likes = 4,
            isLiked = false
        ),
        Comment(
            id = "c10",
            author = sampleAuthors[4],
            content = "Edge cases are definitely important to consider.",
            timestamp = System.currentTimeMillis() - 500000,
            likes = 8,
            isLiked = true
        ),
        Comment(
            id = "c11",
            author = sampleAuthors[0],
            content = "In my experience, the edge cases usually reveal the most interesting insights.",
            timestamp = System.currentTimeMillis() - 400000,
            likes = 12,
            isLiked = false
        ),
        Comment(
            id = "c12",
            author = sampleAuthors[1],
            content = "Absolutely! That's where the real learning happens.",
            timestamp = System.currentTimeMillis() - 300000,
            likes = 9,
            isLiked = true
        ),
        Comment(
            id = "c13",
            author = sampleAuthors[2],
            content = "This thread has been really enlightening. Thanks everyone!",
            timestamp = System.currentTimeMillis() - 200000,
            likes = 15,
            isLiked = false
        )
    )
}
