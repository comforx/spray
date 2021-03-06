/*
 * Copyright (C) 2011-2012 spray.cc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cc.spray
package directives

import typeconversion._

private[spray] trait ReceptaclePimps {
  implicit def fromSymbol(name: Symbol) = NameReceptacle[String](name.name)
  implicit def fromString(name: String) = NameReceptacle[String](name)
}

case class NameReceptacle[A](name: String) {
  def as[B] = NameReceptacle[B](name)
  def as[B](deserializer: FromStringOptionDeserializer[B]) = NameDeserializerReceptacle(name, deserializer)
  def ? = as[Option[A]]
  def ? [B](default: B) = NameDefaultReceptacle(name, default)
  def ! [B](requiredValue: B) = RequiredValueReceptacle(name, requiredValue)
}

case class NameDeserializerReceptacle[A](name: String, deserializer: FromStringOptionDeserializer[A]) {
  def ? = NameDeserializerReceptacle(name, Deserializer.liftToTargetOption(deserializer))
  def ? (default: A) = NameDeserializerDefaultReceptacle(name, deserializer, default)
  def ! (requiredValue: A) = RequiredValueDeserializerReceptacle(name, deserializer, requiredValue)
}

case class NameDefaultReceptacle[A](name: String, default: A)

case class RequiredValueReceptacle[A](name: String, requiredValue: A)

case class NameDeserializerDefaultReceptacle[A](name: String, deserializer: FromStringOptionDeserializer[A], default: A)

case class RequiredValueDeserializerReceptacle[A](name: String, deserializer: FromStringOptionDeserializer[A], requiredValue: A)