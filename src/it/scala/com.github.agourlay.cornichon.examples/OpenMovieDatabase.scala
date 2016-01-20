package com.github.agourlay.cornichon.examples

import com.github.agourlay.cornichon.CornichonFeature

class OpenMovieDatabase extends CornichonFeature {

  override lazy val baseUrl = "http://www.omdbapi.com"

  def feature =
    Feature("OpenMovieDatabase API") {

      Scenario("search for GOT") {

        When I GET("/", params = "t" -> "Game of Thrones")

        Then assert status_is(200)

        And assert body_is(
          """
          {
            "Title": "Game of Thrones",
            "Year": "2011–",
            "Rated": "TV-MA",
            "Released": "17 Apr 2011",
            "Runtime": "56 min",
            "Genre": "Adventure, Drama, Fantasy",
            "Director": "N/A",
            "Writer": "David Benioff, D.B. Weiss",
            "Actors": "Peter Dinklage, Lena Headey, Emilia Clarke, Kit Harington",
            "Plot": "Several noble families fight for control of the mythical land of Westeros.",
            "Language": "English",
            "Country": "USA",
            "Poster": "http://ia.media-imdb.com/images/M/MV5BMTYwOTEzMDMzMl5BMl5BanBnXkFtZTgwNzExODIzNzE@._V1_SX300.jpg",
            "Metascore": "N/A",
            "imdbID": "tt0944947",
            "Type": "series",
            "Response": "True"
          }
          """, ignoring = "imdbRating", "imdbVotes", "Awards"
        )

        And assert body_json_path_is("imdbRating", "9.5")

        And assert body_is(whiteList = true,
          """
          {
            "Title": "Game of Thrones",
            "Year": "2011–",
            "Rated": "TV-MA",
            "Released": "17 Apr 2011"
          }
          """
        )

        And assert headers_contain("Server" -> "cloudflare-nginx")

      }

      Scenario("list GOT season 1 episodes") {

        When I GET("/", params = "t" -> "Game of Thrones", "Season" -> "1")

        Then assert status_is(200)

        And assert body_is("""
          {
            "Title": "Game of Thrones",
            "Season": "1"
          }
          """, ignoring = "Episodes", "Response")

        And assert body_is(
          """
          {
            "Title": "Game of Thrones",
            "Season": "1",
            "Episodes": [
              {
                "Title": "Winter Is Coming",
                "Released": "2011-04-17",
                "Episode": "1",
                "imdbRating": "8.1",
                "imdbID": "tt1480055"
              },
              {
                "Title": "The Kingsroad",
                "Released": "2011-04-24",
                "Episode": "2",
                "imdbRating": "7.8",
                "imdbID": "tt1668746"
              },
              {
                "Title": "Lord Snow",
                "Released": "2011-05-01",
                "Episode": "3",
                "imdbRating": "8.6",
                "imdbID": "tt1829962"
              },
              {
                "Title": "Cripples, Bastards, and Broken Things",
                "Released": "2011-05-08",
                "Episode": "4",
                "imdbRating": "8.7",
                "imdbID": "tt1829963"
              },
              {
                "Title": "The Wolf and the Lion",
                "Released": "2011-05-15",
                "Episode": "5",
                "imdbRating": "9.0",
                "imdbID": "tt1829964"
              },
              {
                "Title": "A Golden Crown",
                "Released": "2011-05-22",
                "Episode": "6",
                "imdbRating": "8.1",
                "imdbID": "tt1837862"
              },
              {
                "Title": "You Win or You Die",
                "Released": "2011-05-29",
                "Episode": "7",
                "imdbRating": "9.2",
                "imdbID": "tt1837863"
              },
              {
                "Title": "The Pointy End",
                "Released": "2011-06-05",
                "Episode": "8",
                "imdbRating": "7.9",
                "imdbID": "tt1837864"
              },
              {
                "Title": "Baelor",
                "Released": "2011-06-12",
                "Episode": "9",
                "imdbRating": "8.5",
                "imdbID": "tt1851398"
              },
              {
                "Title": "Fire and Blood",
                "Released": "2011-06-19",
                "Episode": "10",
                "imdbRating": "8.4",
                "imdbID": "tt1851397"
              }
            ],
            "Response": "True"
          }
          """
        )

        And assert body_json_path_is("Episodes",
          """
            |                Title                    |   Released   | Episode | imdbRating |   imdbID    |
            | "Winter Is Coming"                      | "2011-04-17" |   "1"   |    "8.1"   | "tt1480055" |
            | "The Kingsroad"                         | "2011-04-24" |   "2"   |    "7.8"   | "tt1668746" |
            | "Lord Snow"                             | "2011-05-01" |   "3"   |    "8.6"   | "tt1829962" |
            | "Cripples, Bastards, and Broken Things" | "2011-05-08" |   "4"   |    "8.7"   | "tt1829963" |
            | "The Wolf and the Lion"                 | "2011-05-15" |   "5"   |    "9.0"   | "tt1829964" |
            | "A Golden Crown"                        | "2011-05-22" |   "6"   |    "8.1"   | "tt1837862" |
            | "You Win or You Die"                    | "2011-05-29" |   "7"   |    "9.2"   | "tt1837863" |
            | "The Pointy End"                        | "2011-06-05" |   "8"   |    "7.9"   | "tt1837864" |
            | "Baelor"                                | "2011-06-12" |   "9"   |    "8.5"   | "tt1851398" |
            | "Fire and Blood"                        | "2011-06-19" |  "10"   |    "8.4"   | "tt1851397" |
          """)

        And assert body_array_size_is("Episodes", 10)

        And assert body_json_path_is("Episodes[0]",
          """
          {
            "Title": "Winter Is Coming",
            "Released": "2011-04-17",
            "Episode": "1",
            "imdbRating": "8.1",
            "imdbID": "tt1480055"
          }
          """)

        And assert body_json_path_is("Episodes[0].Released", "2011-04-17")

        And assert body_array_contains("Episodes", """
          {
            "Title": "Winter Is Coming",
            "Released": "2011-04-17",
            "Episode": "1",
            "imdbRating": "8.1",
            "imdbID": "tt1480055"
          }
          """)
      }
    }
}