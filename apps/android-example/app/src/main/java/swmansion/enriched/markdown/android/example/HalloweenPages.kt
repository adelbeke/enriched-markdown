package swmansion.enriched.markdown.android.example

/**
 * One page of the guest book per room, all built from the same template so the card keeps the
 * same height: a heading, an italic note with the room's label as inline code and a ==marked==
 * phrase, three entries with the answer hidden, a line someone left with a hidden ending, a rule,
 * and a sign-off. Every line is short enough to stay on one line at the page's type size.
 */
object HalloweenPages {
  private class Page(
    val title: String,
    val code: String,
    val note: String,
    val entries: List<Pair<String, String>>,
    val saying: Pair<String, String>,
    val closer: String,
  ) {
    val markdown: String
      get() =
        buildList {
          add("## $title")
          add("*`$code` · $note*")
          entries.forEach { (lead, secret) -> add("**$lead** ||$secret||") }
          add("> ${saying.first} ||${saying.second}||")
          add("---")
          add(closer)
        }.joinToString("\n\n")
  }

  fun markdown(room: Room): String = pages.getValue(room).markdown

  private val pages =
    mapOf(
      Room.Attic to
        Page(
          title = "The Attic 🕸️",
          code = "ATTIC",
          note = "Dust ==since 1891==.",
          entries =
            listOf(
              "Silk is stronger than" to "steel, by weight.",
              "Most spiders have" to "eight eyes.",
              "The last guest left" to "in a hurry.",
            ),
          saying = "Walk into my parlour," to "said the spider.",
          closer = "Mind the webs 🕷️",
        ),
      Room.Crypt to
        Page(
          title = "The Crypt 🪦",
          code = "CRYPT",
          note = "==Cold spots== reported.",
          entries =
            listOf(
              "Buried here in 1666:" to "nobody knows.",
              "The fog smells of" to "old candle wax.",
              "The footsteps are" to "the caretaker's.",
            ),
          saying = "Something wicked" to "this way comes.",
          closer = "Watch your step 🦴",
        ),
      Room.Porch to
        Page(
          title = "The Porch 🎃",
          code = "PORCH",
          note = "Carving ==at dusk==.",
          entries =
            listOf(
              "The first lanterns were" to "carved turnips.",
              "The record pumpkin:" to "over 1,200 kg.",
              "A pumpkin is, botanically," to "a berry.",
            ),
          saying = "Trick" to "or treat?",
          closer = "Light the candle 🕯️",
        ),
      Room.Nursery to
        Page(
          title = "The Nursery 👻",
          code = "NURSERY",
          note = "==Do not== look under the bed.",
          entries =
            listOf(
              "The rocking chair rocks" to "on its own.",
              "Her lullaby was" to "Hush, Little Baby.",
              "The music box plays" to "at 3:33 am.",
            ),
          saying = "Boo!" to "Did I scare you?",
          closer = "Sweet dreams 🧸",
        ),
      Room.Belfry to
        Page(
          title = "The Belfry 🦇",
          code = "BELFRY",
          note = "Roosting ==upside down==.",
          entries =
            listOf(
              "One bat eats, per night," to "1,000 mosquitoes.",
              "The only flying mammals:" to "bats.",
              "A vampire bat drinks" to "about a spoonful.",
            ),
          saying = "The bell tolls" to "for thee.",
          closer = "Duck! 🔔",
        ),
      Room.Kitchen to
        Page(
          title = "The Kitchen 🔪",
          code = "KITCHEN",
          note = "Tonight's special: ==red velvet==.",
          entries =
            listOf(
              "Stage blood is mostly" to "corn syrup.",
              "Dracula was written by" to "Bram Stoker.",
              "The stain is" to "beetroot, allegedly.",
            ),
          saying = "I never drink…" to "wine.",
          closer = "Bon appétit 🍷",
        ),
      Room.Cellar to
        Page(
          title = "The Cellar 🕯️",
          code = "CELLAR",
          note = "==No power== below stairs.",
          entries =
            listOf(
              "Behind the third barrel:" to "a locked door.",
              "The key hangs" to "around your neck.",
              "The candle lasts" to "eleven minutes.",
            ),
          saying = "Whatever you do," to "don't blow it out.",
          closer = "Keep the flame 🔥",
        ),
    )
}
