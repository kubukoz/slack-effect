package slack.internal

import io.circe.generic.extras.Configuration

private[slack] trait CirceConfig {
  implicit val config: Configuration =
    Configuration(Configuration.snakeCaseTransformation, Configuration.snakeCaseTransformation, useDefaults = false, discriminator = Some("type"))
}
